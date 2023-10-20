/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles.base.viewmodel

import androidx.annotation.CallSuper
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.tiles.base.analytics.QSTileAnalytics
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.DisabledByPolicyInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.logging.QSTileLogger
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTilePolicy
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.throttle
import com.android.systemui.util.time.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * Provides a hassle-free way to implement new tiles according to current System UI architecture
 * standards. This ViewModel is cheap to instantiate and does nothing until its [state] is listened.
 *
 * Don't use this constructor directly. Instead, inject [QSViewModelFactory] to create a new
 * instance of this class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BaseQSTileViewModel<DATA_TYPE>(
    val tileConfig: () -> QSTileConfig,
    private val userActionInteractor: () -> QSTileUserActionInteractor<DATA_TYPE>,
    private val tileDataInteractor: () -> QSTileDataInteractor<DATA_TYPE>,
    private val mapper: () -> QSTileDataToStateMapper<DATA_TYPE>,
    private val disabledByPolicyInteractor: DisabledByPolicyInteractor,
    userRepository: UserRepository,
    private val falsingManager: FalsingManager,
    private val qsTileAnalytics: QSTileAnalytics,
    private val qsTileLogger: QSTileLogger,
    private val systemClock: SystemClock,
    private val backgroundDispatcher: CoroutineDispatcher,
    private val tileScope: CoroutineScope = CoroutineScope(SupervisorJob()),
) : QSTileViewModel {

    private val userIds: MutableStateFlow<Int> =
        MutableStateFlow(userRepository.getSelectedUserInfo().id)
    private val userInputs: MutableSharedFlow<QSTileUserAction> =
        MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val forceUpdates: MutableSharedFlow<Unit> =
        MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val spec
        get() = config.tileSpec

    private val tileData: SharedFlow<DATA_TYPE> = createTileDataFlow()

    override val config
        get() = tileConfig()
    override val state: SharedFlow<QSTileState> =
        tileData
            .map { data ->
                mapper().map(config, data).also { state ->
                    qsTileLogger.logStateUpdate(spec, state, data)
                }
            }
            .flowOn(backgroundDispatcher)
            .shareIn(
                tileScope,
                SharingStarted.WhileSubscribed(),
                replay = 1,
            )
    override val isAvailable: StateFlow<Boolean> =
        userIds
            .flatMapLatest { tileDataInteractor().availability(it) }
            .flowOn(backgroundDispatcher)
            .stateIn(
                tileScope,
                SharingStarted.WhileSubscribed(),
                true,
            )

    @CallSuper
    override fun forceUpdate() {
        forceUpdates.tryEmit(Unit)
    }

    @CallSuper
    override fun onUserIdChanged(userId: Int) {
        userIds.tryEmit(userId)
    }

    @CallSuper
    override fun onActionPerformed(userAction: QSTileUserAction) {
        qsTileLogger.logUserAction(
            userAction,
            spec,
            tileData.replayCache.isNotEmpty(),
            state.replayCache.isNotEmpty()
        )
        userInputs.tryEmit(userAction)
    }

    override fun destroy() {
        tileScope.cancel()
    }

    private fun createTileDataFlow(): SharedFlow<DATA_TYPE> =
        userIds
            .flatMapLatest { userId ->
                val updateTriggers =
                    merge(
                            userInputFlow(userId),
                            forceUpdates
                                .map { DataUpdateTrigger.ForceUpdate }
                                .onEach { qsTileLogger.logForceUpdate(spec) },
                        )
                        .onStart {
                            emit(DataUpdateTrigger.InitialRequest)
                            qsTileLogger.logInitialRequest(spec)
                        }
                tileDataInteractor()
                    .tileData(userId, updateTriggers)
                    .cancellable()
                    .flowOn(backgroundDispatcher)
            }
            .shareIn(
                tileScope,
                SharingStarted.WhileSubscribed(),
                replay = 1, // we only care about the most recent value
            )

    /**
     * Creates a user input flow which:
     * - filters false inputs with [falsingManager]
     * - takes care of a tile being disable by policy using [disabledByPolicyInteractor]
     * - notifies [userActionInteractor] about the action
     * - logs it accordingly using [qsTileLogger] and [qsTileAnalytics]
     *
     * Subscribing to the result flow twice will result in doubling all actions, logs and analytics.
     */
    private fun userInputFlow(userId: Int): Flow<DataUpdateTrigger> {
        return userInputs
            .filterFalseActions()
            .filterByPolicy(userId)
            .throttle(CLICK_THROTTLE_DURATION, systemClock)
            // Skip the input until there is some data
            .mapNotNull { action ->
                val state: QSTileState = state.replayCache.lastOrNull() ?: return@mapNotNull null
                val data: DATA_TYPE = tileData.replayCache.lastOrNull() ?: return@mapNotNull null
                qsTileLogger.logUserActionPipeline(spec, action, state, data)
                qsTileAnalytics.trackUserAction(config, action)

                DataUpdateTrigger.UserInput(QSTileInput(userId, action, data))
            }
            .onEach { userActionInteractor().handleInput(it.input) }
            .flowOn(backgroundDispatcher)
    }

    private fun Flow<QSTileUserAction>.filterByPolicy(userId: Int): Flow<QSTileUserAction> =
        config.policy.let { policy ->
            when (policy) {
                is QSTilePolicy.NoRestrictions -> this@filterByPolicy
                is QSTilePolicy.Restricted ->
                    filter { action ->
                        val result =
                            disabledByPolicyInteractor.isDisabled(userId, policy.userRestriction)
                        !disabledByPolicyInteractor.handlePolicyResult(result).also { isDisabled ->
                            if (isDisabled) {
                                qsTileLogger.logUserActionRejectedByPolicy(action, spec)
                            }
                        }
                    }
            }
        }

    private fun Flow<QSTileUserAction>.filterFalseActions(): Flow<QSTileUserAction> =
        filter { action ->
            val isFalseAction =
                when (action) {
                    is QSTileUserAction.Click ->
                        falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)
                    is QSTileUserAction.LongClick ->
                        falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)
                }
            if (isFalseAction) {
                qsTileLogger.logUserActionRejectedByFalsing(action, spec)
            }
            !isFalseAction
        }

    private companion object {
        const val CLICK_THROTTLE_DURATION = 200L
    }
}
