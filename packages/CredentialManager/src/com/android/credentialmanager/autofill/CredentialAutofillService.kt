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

package com.android.credentialmanager.autofill

import android.app.assist.AssistStructure
import android.content.Context
import android.credentials.CredentialManager
import android.credentials.CredentialOption
import android.credentials.GetCandidateCredentialsException
import android.credentials.GetCandidateCredentialsResponse
import android.credentials.GetCredentialRequest
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.service.credentials.CredentialProviderService
import android.util.Log
import android.view.autofill.AutofillId
import org.json.JSONException
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.android.credentialmanager.GetFlowUtils
import com.android.credentialmanager.getflow.CredentialEntryInfo
import com.android.credentialmanager.getflow.ProviderDisplayInfo
import com.android.credentialmanager.getflow.toProviderDisplayInfo
import org.json.JSONObject
import java.util.concurrent.Executors

class CredentialAutofillService : AutofillService() {

    companion object {
        private const val TAG = "CredAutofill"

        private const val CRED_HINT_PREFIX = "credential="
        private const val REQUEST_DATA_KEY = "requestData"
        private const val CANDIDATE_DATA_KEY = "candidateQueryData"
        private const val SYS_PROVIDER_REQ_KEY = "isSystemProviderRequired"
        private const val CRED_OPTIONS_KEY = "credentialOptions"
        private const val TYPE_KEY = "type"
        private const val REQ_TYPE_KEY = "get"
    }

    override fun onFillRequest(
            request: FillRequest,
            cancellationSignal: CancellationSignal,
            callback: FillCallback
    ) {
        val context = request.fillContexts
        val structure = context[context.size - 1].structure
        val callingPackage = structure.activityComponent.packageName
        Log.i(TAG, "onFillRequest called for $callingPackage")

        val getCredRequest: GetCredentialRequest? = getCredManRequest(structure)
        if (getCredRequest == null) {
            Log.i(TAG, "No credential manager request found")
            callback.onFailure("No credential manager request found")
            return
        }
        val credentialManager: CredentialManager =
                getSystemService(Context.CREDENTIAL_SERVICE) as CredentialManager

        val outcome = object : OutcomeReceiver<GetCandidateCredentialsResponse,
                GetCandidateCredentialsException> {
            override fun onResult(result: GetCandidateCredentialsResponse) {
                Log.i(TAG, "getCandidateCredentials onResponse")
                val fillResponse = convertToFillResponse(result, request)
                if (fillResponse != null) {
                    callback.onSuccess(fillResponse)
                } else {
                    Log.e(TAG, "Failed to create a FillResponse from the CredentialResponse.")
                    callback.onFailure("No dataset was created from the CredentialResponse")
                }
            }

            override fun onError(error: GetCandidateCredentialsException) {
                Log.i(TAG, "getCandidateCredentials onError")
                callback.onFailure("error received from credential manager ${error.message}")
            }
        }

        credentialManager.getCandidateCredentials(
                getCredRequest,
                callingPackage,
                CancellationSignal(),
                Executors.newSingleThreadExecutor(),
                outcome
        )
    }

    private fun convertToFillResponse(
            getCredResponse: GetCandidateCredentialsResponse,
            filLRequest: FillRequest
    ): FillResponse? {
        val providerList = GetFlowUtils.toProviderList(
                getCredResponse.candidateProviderDataList,
                this@CredentialAutofillService)
        if (providerList.isEmpty()) {
            return null
        }
        var totalEntryCount = 0
        providerList.forEach { provider ->
            totalEntryCount += provider.credentialEntryList.size
        }
        val providerDisplayInfo: ProviderDisplayInfo = toProviderDisplayInfo(providerList)
        val inlineSuggestionsRequest = filLRequest.inlineSuggestionsRequest
        val inlineMaxSuggestedCount = inlineSuggestionsRequest?.maxSuggestionCount ?: 0
        val inlinePresentationSpecs = inlineSuggestionsRequest?.inlinePresentationSpecs
        val inlinePresentationSpecsCount = inlinePresentationSpecs?.size ?: 0
        var maxItemCount = totalEntryCount
        if (inlineMaxSuggestedCount > 0) {
            maxItemCount = maxItemCount.coerceAtMost(inlineMaxSuggestedCount)
        }
        var i = 0
        val fillResponseBuilder = FillResponse.Builder()
        var emptyFillResponse = true

        providerDisplayInfo.sortedUserNameToCredentialEntryList.forEach usernameLoop@ {
            val primaryEntry = it.sortedCredentialEntryList.first()
            // In regular CredMan bottomsheet, only one primary entry per username is displayed.
            // But since the credential requests from different fields are allocated into a single
            // request for autofill, there will be duplicate primary entries, especially for
            // username/pw autofill fields. These primary entries will be the same entries except
            // their autofillIds will point to different autofill fields. Process all primary
            // fields.
            // TODO(b/307435163): Merge credential options
            it.sortedCredentialEntryList.forEach entryLoop@ { credentialEntry ->
                if (!isSameCredentialEntry(primaryEntry, credentialEntry)) {
                    // Encountering different credential entry means all the duplicate primary
                    // entries have been processed.
                    return@usernameLoop
                }
                val autofillId: AutofillId? = credentialEntry
                        .fillInIntent
                        ?.getParcelableExtra(
                                CredentialProviderService.EXTRA_AUTOFILL_ID,
                                AutofillId::class.java)
                val pendingIntent = credentialEntry.pendingIntent
                if (autofillId == null || pendingIntent == null) {
                    Log.e(TAG, "AutofillId or pendingIntent was missing from the entry.")
                    return@entryLoop
                }
                var inlinePresentation: InlinePresentation? = null
                // Create inline presentation
                if (inlinePresentationSpecs != null && i < maxItemCount) {
                    val spec: InlinePresentationSpec
                    if (i < inlinePresentationSpecsCount) {
                        spec = inlinePresentationSpecs[i]
                    } else {
                        spec = inlinePresentationSpecs[inlinePresentationSpecsCount - 1]
                    }
                    val sliceBuilder = InlineSuggestionUi
                            .newContentBuilder(pendingIntent)
                            .setTitle(credentialEntry.userName)
                    inlinePresentation = InlinePresentation(
                            sliceBuilder.build().slice, spec, /* pinned= */ false)
                }
                i++

                val dataSetBuilder = Dataset.Builder()
                val presentationBuilder = Presentations.Builder()
                if (inlinePresentation != null) {
                    presentationBuilder.setInlinePresentation(inlinePresentation)
                }
                fillResponseBuilder.addDataset(
                        dataSetBuilder
                                .setField(
                                        autofillId,
                                        Field.Builder().setPresentations(
                                                presentationBuilder.build())
                                                .build())
                                .setAuthentication(pendingIntent.intentSender)
                                .setAuthenticationExtras(credentialEntry.fillInIntent.extras)
                                .build())
                emptyFillResponse = false
            }
        }
        if (emptyFillResponse) {
            return null
        }
        return fillResponseBuilder.build()
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        TODO("Not yet implemented")
    }

    private fun getCredManRequest(structure: AssistStructure): GetCredentialRequest? {
        val credentialOptions: MutableList<CredentialOption> = mutableListOf()
        traverseStructure(structure, credentialOptions)

        if (credentialOptions.isNotEmpty()) {
            return GetCredentialRequest.Builder(Bundle.EMPTY)
                    .setCredentialOptions(credentialOptions)
                    .build()
        }
        return null
    }

    private fun traverseStructure(
            structure: AssistStructure,
            cmRequests: MutableList<CredentialOption>
    ) {
        val windowNodes: List<AssistStructure.WindowNode> =
                structure.run {
                    (0 until windowNodeCount).map { getWindowNodeAt(it) }
                }

        windowNodes.forEach { windowNode: AssistStructure.WindowNode ->
            traverseNode(windowNode.rootViewNode, cmRequests)
        }
    }

    private fun traverseNode(
            viewNode: AssistStructure.ViewNode,
            cmRequests: MutableList<CredentialOption>
    ) {
        viewNode.autofillId?.let {
            val options = getCredentialOptionsFromViewNode(viewNode, it)
            cmRequests.addAll(options)
        }

        val children: List<AssistStructure.ViewNode> =
                viewNode.run {
                    (0 until childCount).map { getChildAt(it) }
                }

        children.forEach { childNode: AssistStructure.ViewNode ->
            traverseNode(childNode, cmRequests)
        }
    }

    private fun getCredentialOptionsFromViewNode(
            viewNode: AssistStructure.ViewNode,
            autofillId: AutofillId
    ): List<CredentialOption> {
        // TODO(b/293945193) Replace with isCredential check from viewNode
        val credentialHints: MutableList<String> = mutableListOf()
        if (viewNode.autofillHints != null) {
            for (hint in viewNode.autofillHints!!) {
                if (hint.startsWith(CRED_HINT_PREFIX)) {
                    credentialHints.add(hint.substringAfter(CRED_HINT_PREFIX))
                }
            }
        }

        val credentialOptions: MutableList<CredentialOption> = mutableListOf()
        for (credentialHint in credentialHints) {
            try {
                convertJsonToCredentialOption(credentialHint, autofillId)
                        .let { credentialOptions.addAll(it) }
            } catch (e: JSONException) {
                Log.i(TAG, "Exception while parsing response: " + e.message)
            }
        }
        return credentialOptions
    }

    private fun convertJsonToCredentialOption(jsonString: String, autofillId: AutofillId):
            List<CredentialOption> {
        // TODO(b/302000646) Move this logic to jetpack so that is consistent
        //  with building the json
        val credentialOptions: MutableList<CredentialOption> = mutableListOf()

        val json = JSONObject(jsonString)
        val jsonGet = json.getJSONObject(REQ_TYPE_KEY)
        val options = jsonGet.getJSONArray(CRED_OPTIONS_KEY)
        for (i in 0 until options.length()) {
            val option = options.getJSONObject(i)
            val candidateBundle = convertJsonToBundle(option.getJSONObject(CANDIDATE_DATA_KEY))
            candidateBundle.putParcelable(
                    CredentialProviderService.EXTRA_AUTOFILL_ID,
                    autofillId)
            credentialOptions.add(CredentialOption(
                    option.getString(TYPE_KEY),
                    convertJsonToBundle(option.getJSONObject(REQUEST_DATA_KEY)),
                    candidateBundle,
                    option.getBoolean(SYS_PROVIDER_REQ_KEY),
            ))
        }
        return credentialOptions
    }

    private fun convertJsonToBundle(json: JSONObject): Bundle {
        val result = Bundle()
        json.keys().forEach {
            val v = json.get(it)
            when (v) {
                is String -> result.putString(it, v)
                is Boolean -> result.putBoolean(it, v)
            }
        }
        return result
    }

    private fun isSameCredentialEntry(
            info1: CredentialEntryInfo,
            info2: CredentialEntryInfo
    ): Boolean {
        return info1.providerId == info2.providerId &&
                info1.lastUsedTimeMillis == info2.lastUsedTimeMillis &&
                info1.credentialType == info2.credentialType &&
                info1.displayName == info2.displayName &&
                info1.userName == info2.userName
    }
}