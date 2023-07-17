/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.power.domain.interactor

import android.os.PowerManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.WakeSleepReason
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class PowerInteractorTest : SysuiTestCase() {

    private lateinit var underTest: PowerInteractor
    private lateinit var repository: FakePowerRepository
    private val keyguardRepository = FakeKeyguardRepository()
    @Mock private lateinit var falsingCollector: FalsingCollector
    @Mock private lateinit var screenOffAnimationController: ScreenOffAnimationController
    @Mock private lateinit var statusBarStateController: StatusBarStateController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        repository =
            FakePowerRepository(
                initialInteractive = true,
            )
        underTest =
            PowerInteractor(
                repository,
                keyguardRepository,
                falsingCollector,
                screenOffAnimationController,
                statusBarStateController,
            )
    }

    @Test
    fun isInteractive_screenTurnsOff() =
        runBlocking(IMMEDIATE) {
            repository.setInteractive(true)
            var value: Boolean? = null
            val job = underTest.isInteractive.onEach { value = it }.launchIn(this)

            repository.setInteractive(false)

            assertThat(value).isFalse()
            job.cancel()
        }

    @Test
    fun isInteractive_becomesInteractive() =
        runBlocking(IMMEDIATE) {
            repository.setInteractive(false)
            var value: Boolean? = null
            val job = underTest.isInteractive.onEach { value = it }.launchIn(this)

            repository.setInteractive(true)

            assertThat(value).isTrue()
            job.cancel()
        }

    @Test
    fun wakeUpIfDozing_notDozing_notWoken() {
        whenever(statusBarStateController.isDozing).thenReturn(false)
        whenever(screenOffAnimationController.allowWakeUpIfDozing()).thenReturn(true)

        underTest.wakeUpIfDozing("why", PowerManager.WAKE_REASON_TAP)

        assertThat(repository.lastWakeWhy).isNull()
        assertThat(repository.lastWakeReason).isNull()
    }

    @Test
    fun wakeUpIfDozing_notAllowed_notWoken() {
        whenever(screenOffAnimationController.allowWakeUpIfDozing()).thenReturn(false)
        whenever(statusBarStateController.isDozing).thenReturn(true)

        underTest.wakeUpIfDozing("why", PowerManager.WAKE_REASON_TAP)

        assertThat(repository.lastWakeWhy).isNull()
        assertThat(repository.lastWakeReason).isNull()
    }

    @Test
    fun wakeUpIfDozing_dozingAndAllowed_wokenAndFalsingNotified() {
        whenever(statusBarStateController.isDozing).thenReturn(true)
        whenever(screenOffAnimationController.allowWakeUpIfDozing()).thenReturn(true)

        underTest.wakeUpIfDozing("testReason", PowerManager.WAKE_REASON_GESTURE)

        assertThat(repository.lastWakeWhy).isEqualTo("testReason")
        assertThat(repository.lastWakeReason).isEqualTo(PowerManager.WAKE_REASON_GESTURE)
        verify(falsingCollector).onScreenOnFromTouch()
    }

    @Test
    fun wakeUpForFullScreenIntent_notGoingToSleepAndNotDozing_notWoken() {
        keyguardRepository.setWakefulnessModel(
            WakefulnessModel(
                state = WakefulnessState.AWAKE,
                lastWakeReason = WakeSleepReason.OTHER,
                lastSleepReason = WakeSleepReason.OTHER,
            )
        )
        whenever(statusBarStateController.isDozing).thenReturn(false)

        underTest.wakeUpForFullScreenIntent()

        assertThat(repository.lastWakeWhy).isNull()
        assertThat(repository.lastWakeReason).isNull()
    }

    @Test
    fun wakeUpForFullScreenIntent_startingToSleep_woken() {
        keyguardRepository.setWakefulnessModel(
            WakefulnessModel(
                state = WakefulnessState.STARTING_TO_SLEEP,
                lastWakeReason = WakeSleepReason.OTHER,
                lastSleepReason = WakeSleepReason.OTHER,
            )
        )
        whenever(statusBarStateController.isDozing).thenReturn(false)

        underTest.wakeUpForFullScreenIntent()

        assertThat(repository.lastWakeWhy).isNotNull()
        assertThat(repository.lastWakeReason).isEqualTo(PowerManager.WAKE_REASON_APPLICATION)
    }

    @Test
    fun wakeUpForFullScreenIntent_dozing_woken() {
        whenever(statusBarStateController.isDozing).thenReturn(true)
        keyguardRepository.setWakefulnessModel(
            WakefulnessModel(
                state = WakefulnessState.AWAKE,
                lastWakeReason = WakeSleepReason.OTHER,
                lastSleepReason = WakeSleepReason.OTHER,
            )
        )

        underTest.wakeUpForFullScreenIntent()

        assertThat(repository.lastWakeWhy).isNotNull()
        assertThat(repository.lastWakeReason).isEqualTo(PowerManager.WAKE_REASON_APPLICATION)
    }

    @Test
    fun wakeUpIfDreaming_dreaming_woken() {
        // GIVEN device is dreaming
        whenever(statusBarStateController.isDreaming).thenReturn(true)

        // WHEN wakeUpIfDreaming is called
        underTest.wakeUpIfDreaming("testReason", PowerManager.WAKE_REASON_GESTURE)

        // THEN device is woken up
        assertThat(repository.lastWakeWhy).isEqualTo("testReason")
        assertThat(repository.lastWakeReason).isEqualTo(PowerManager.WAKE_REASON_GESTURE)
    }

    @Test
    fun wakeUpIfDreaming_notDreaming_notWoken() {
        // GIVEN device is not dreaming
        whenever(statusBarStateController.isDreaming).thenReturn(false)

        // WHEN wakeUpIfDreaming is called
        underTest.wakeUpIfDreaming("why", PowerManager.WAKE_REASON_TAP)

        // THEN device is not woken
        assertThat(repository.lastWakeWhy).isNull()
        assertThat(repository.lastWakeReason).isNull()
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
