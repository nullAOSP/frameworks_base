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
 */

package com.android.systemui.accessibility;

import static android.provider.Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_CAPABILITY;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.IdRes;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.accessibility.common.MagnificationConstants;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class WindowMagnificationSettingsTest extends SysuiTestCase {

    private static final int MAGNIFICATION_SIZE_SMALL = 1;
    private static final int MAGNIFICATION_SIZE_MEDIUM = 2;
    private static final int MAGNIFICATION_SIZE_LARGE = 3;

    private ViewGroup mSettingView;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private SfVsyncFrameCallbackProvider mSfVsyncFrameProvider;
    @Mock
    private SecureSettings mSecureSettings;
    @Mock
    private WindowMagnificationSettingsCallback mWindowMagnificationSettingsCallback;
    private TestableWindowManager mWindowManager;
    private WindowMagnificationSettings mWindowMagnificationSettings;
    private MotionEventHelper mMotionEventHelper = new MotionEventHelper();

    private ArgumentCaptor<Float> mSecureSettingsScaleCaptor;
    private ArgumentCaptor<String> mSecureSettingsNameCaptor;
    private ArgumentCaptor<Integer> mSecureSettingsUserHandleCaptor;
    private ArgumentCaptor<Float> mCallbackMagnifierScaleCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = getContext();
        mContext.setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        mWindowManager = spy(new TestableWindowManager(wm));
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);
        mContext.addMockSystemService(Context.ACCESSIBILITY_SERVICE, mAccessibilityManager);

        mWindowMagnificationSettings = new WindowMagnificationSettings(mContext,
                mWindowMagnificationSettingsCallback, mSfVsyncFrameProvider,
                mSecureSettings);

        mSettingView = mWindowMagnificationSettings.getSettingView();
        mSecureSettingsScaleCaptor = ArgumentCaptor.forClass(Float.class);
        mSecureSettingsNameCaptor = ArgumentCaptor.forClass(String.class);
        mSecureSettingsUserHandleCaptor = ArgumentCaptor.forClass(Integer.class);
        mCallbackMagnifierScaleCaptor = ArgumentCaptor.forClass(Float.class);
    }

    @After
    public void tearDown() {
        mMotionEventHelper.recycleEvents();
        mWindowMagnificationSettings.hideSettingPanel();
    }

    @Test
    public void showSettingPanel_hasAccessibilityWindowTitle() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mWindowMagnificationSettings.showSettingPanel();

        final WindowManager.LayoutParams layoutPrams =
                mWindowManager.getLayoutParamsFromAttachedView();
        assertNotNull(layoutPrams);
        assertEquals(getContext().getResources()
                        .getString(com.android.internal.R.string.android_system_label),
                layoutPrams.accessibilityTitle.toString());
    }

    @Test
    public void showSettingPanel_windowMode_showEditButtonAndDiagonalView() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mWindowMagnificationSettings.showSettingPanel();

        final Button editButton = getInternalView(R.id.magnifier_edit_button);
        assertEquals(editButton.getVisibility(), View.VISIBLE);

        final LinearLayout diagonalView = getInternalView(R.id.magnifier_horizontal_lock_view);
        assertEquals(diagonalView.getVisibility(), View.VISIBLE);
    }

    @Test
    public void showSettingPanel_fullScreenMode_hideEditButtonAndDiagonalView() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_ALL,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        mWindowMagnificationSettings.showSettingPanel();

        final Button editButton = getInternalView(R.id.magnifier_edit_button);
        assertEquals(editButton.getVisibility(), View.INVISIBLE);

        final LinearLayout diagonalView = getInternalView(R.id.magnifier_horizontal_lock_view);
        assertEquals(diagonalView.getVisibility(), View.GONE);
    }

    @Test
    public void showSettingPanel_windowOnlyCapability_hideFullscreenButton() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mWindowMagnificationSettings.showSettingPanel();

        final View fullscreenButton = getInternalView(R.id.magnifier_full_button);
        assertThat(fullscreenButton.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void performClick_smallSizeButton_changeMagnifierSizeSmallAndSwitchToWindowMode() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mWindowMagnificationSettings.showSettingPanel();

        verifyOnSetMagnifierSizeAndOnModeSwitch(
                R.id.magnifier_small_button, MAGNIFICATION_SIZE_SMALL);
    }

    @Test
    public void performClick_mediumSizeButton_changeMagnifierSizeMediumAndSwitchToWindowMode() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mWindowMagnificationSettings.showSettingPanel();

        verifyOnSetMagnifierSizeAndOnModeSwitch(
                R.id.magnifier_medium_button, MAGNIFICATION_SIZE_MEDIUM);
    }

    @Test
    public void performClick_largeSizeButton_changeMagnifierSizeLargeAndSwitchToWindowMode() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mWindowMagnificationSettings.showSettingPanel();

        verifyOnSetMagnifierSizeAndOnModeSwitch(
                R.id.magnifier_large_button, MAGNIFICATION_SIZE_LARGE);
    }

    private void verifyOnSetMagnifierSizeAndOnModeSwitch(@IdRes int viewId, int expectedSizeIndex) {
        View changeSizeButton = getInternalView(viewId);

        // Perform click
        changeSizeButton.performClick();

        verify(mWindowMagnificationSettingsCallback).onSetMagnifierSize(expectedSizeIndex);
        verify(mWindowMagnificationSettingsCallback)
                .onModeSwitch(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
    }


    @Test
    public void performClick_fullScreenModeButton_switchToFullScreenMode() {
        View fullScreenModeButton = getInternalView(R.id.magnifier_full_button);
        getInternalView(R.id.magnifier_panel_view);

        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_ALL,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mWindowMagnificationSettings.showSettingPanel();

        // Perform click
        fullScreenModeButton.performClick();

        verify(mWindowMagnificationSettingsCallback)
                .onModeSwitch(Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
    }

    @Test
    public void performClick_editButton_setEditMagnifierSizeMode() {
        View editButton = getInternalView(R.id.magnifier_edit_button);

        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mWindowMagnificationSettings.showSettingPanel();

        // Perform click
        editButton.performClick();

        verify(mWindowMagnificationSettingsCallback).onEditMagnifierSizeMode(true);
        verify(mWindowManager).removeView(mSettingView);
    }

    @Test
    public void performClick_setDiagonalScrollingSwitch_toggleDiagonalScrollingSwitchMode() {
        CompoundButton diagonalScrollingSwitch =
                getInternalView(R.id.magnifier_horizontal_lock_switch);
        final boolean currentCheckedState = diagonalScrollingSwitch.isChecked();

        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mWindowMagnificationSettings.showSettingPanel();

        // Perform click
        diagonalScrollingSwitch.performClick();

        verify(mWindowMagnificationSettingsCallback).onSetDiagonalScrolling(!currentCheckedState);
    }

    @Test
    public void onConfigurationChanged_selectedButtonIsStillSelected() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_ALL,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mWindowMagnificationSettings.showSettingPanel();
        View magnifierMediumButton = getInternalView(R.id.magnifier_medium_button);
        magnifierMediumButton.performClick();

        mWindowMagnificationSettings.onConfigurationChanged(ActivityInfo.CONFIG_UI_MODE);

        // Since the view is re-inflated after onConfigurationChanged,
        // we need to get the view again.
        magnifierMediumButton = getInternalView(R.id.magnifier_medium_button);
        assertThat(magnifierMediumButton.isSelected()).isTrue();
    }

    @Test
    public void onScreenSizeChanged_resetPositionToRightBottomCorner() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_ALL,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mWindowMagnificationSettings.showSettingPanel();

        // move the panel to the center of draggable window bounds
        mWindowMagnificationSettings.mParams.x =
                mWindowMagnificationSettings.mDraggableWindowBounds.centerX();
        mWindowMagnificationSettings.mParams.y =
                mWindowMagnificationSettings.mDraggableWindowBounds.centerY();
        mWindowMagnificationSettings.updateButtonViewLayoutIfNeeded();

        final Rect testWindowBounds = new Rect(
                mWindowManager.getCurrentWindowMetrics().getBounds());
        testWindowBounds.set(testWindowBounds.left, testWindowBounds.top,
                testWindowBounds.right + 200, testWindowBounds.bottom + 50);
        mWindowManager.setWindowBounds(testWindowBounds);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mWindowMagnificationSettings.onConfigurationChanged(ActivityInfo.CONFIG_SCREEN_SIZE);
        });

        // the panel position should be reset to the bottom-right corner
        assertEquals(
                mWindowMagnificationSettings.mParams.x,
                mWindowMagnificationSettings.mDraggableWindowBounds.right);
        assertEquals(
                mWindowMagnificationSettings.mParams.y,
                mWindowMagnificationSettings.mDraggableWindowBounds.bottom);
    }

    @Test
    public void showSettingsPanel_observerRegistered() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_ALL,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        mWindowMagnificationSettings.showSettingPanel();

        verify(mSecureSettings).registerContentObserverForUser(
                eq(ACCESSIBILITY_MAGNIFICATION_CAPABILITY),
                any(ContentObserver.class),
                eq(UserHandle.USER_CURRENT));
    }

    @Test
    public void showSettingsPanel_observerForMagnificationScaleRegistered() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_ALL,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        mWindowMagnificationSettings.showSettingPanel();

        verify(mSecureSettings).registerContentObserverForUser(
                eq(ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE),
                any(ContentObserver.class),
                eq(UserHandle.USER_CURRENT));
    }

    @Test
    public void hideSettingsPanel_observerUnregistered() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_ALL,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        mWindowMagnificationSettings.showSettingPanel();
        mWindowMagnificationSettings.hideSettingPanel();

        verify(mSecureSettings, times(2)).unregisterContentObserver(any(ContentObserver.class));
    }

    @Test
    public void seekbarProgress_justInflated_maxValueAndProgressSetCorrectly() {
        setupScaleInSecureSettings(0f);
        assertThat(mWindowMagnificationSettings.mZoomSeekbar.getProgress()).isEqualTo(0);
        assertThat(mWindowMagnificationSettings.mZoomSeekbar.getMax()).isEqualTo(70);
    }

    @Test
    public void seekbarProgress_minMagnification_seekbarProgressIsCorrect() {
        setupScaleInSecureSettings(0f);
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        mWindowMagnificationSettings.showSettingPanel();

        // Seekbar index from 0 to 70. 1.0f scale (A11Y_SCALE_MIN_VALUE) would correspond to 0.
        assertThat(mWindowMagnificationSettings.mZoomSeekbar.getProgress()).isEqualTo(0);
    }

    @Test
    public void seekbarProgress_belowMinMagnification_seekbarProgressIsZero() {
        setupScaleInSecureSettings(0f);
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        mWindowMagnificationSettings.showSettingPanel();

        assertThat(mWindowMagnificationSettings.mZoomSeekbar.getProgress()).isEqualTo(0);
    }

    @Test
    public void seekbarProgress_magnificationBefore_seekbarProgressIsHalf() {
        setupScaleInSecureSettings(4f);
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        mWindowMagnificationSettings.showSettingPanel();

        // float scale : from 1.0f to 8.0f, seekbar index from 0 to 70.
        // 4.0f would correspond to 30.
        assertThat(mWindowMagnificationSettings.mZoomSeekbar.getProgress()).isEqualTo(30);
    }

    @Test
    public void seekbarProgress_maxMagnificationBefore_seekbarProgressIsMax() {
        setupScaleInSecureSettings(8f);
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        mWindowMagnificationSettings.showSettingPanel();

        // 8.0f is max magnification {@link MagnificationScaleProvider#MAX_SCALE}.
        // Max zoom seek bar is 70.
        assertThat(mWindowMagnificationSettings.mZoomSeekbar.getProgress()).isEqualTo(70);
    }

    @Test
    public void seekbarProgress_aboveMaxMagnificationBefore_seekbarProgressIsMax() {
        setupScaleInSecureSettings(9f);
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        mWindowMagnificationSettings.showSettingPanel();

        // Max zoom seek bar is 70.
        assertThat(mWindowMagnificationSettings.mZoomSeekbar.getProgress()).isEqualTo(70);
    }

    @Test
    public void seekbarProgress_progressChangedRoughlyHalf_scaleAndCallbackUpdated() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mWindowMagnificationSettings.showSettingPanel();

        mWindowMagnificationSettings.mZoomSeekbar.setProgress(30);

        verifyScaleUpdatedInSecureSettings(4f);
        verifyCallbackOnMagnifierScale(4f);
    }

    @Test
    public void seekbarProgress_minProgress_callbackUpdated() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mWindowMagnificationSettings.showSettingPanel();
        // Set progress to non-zero first so onProgressChanged can be triggered upon setting to 0.
        mWindowMagnificationSettings.mZoomSeekbar.setProgress(30);

        mWindowMagnificationSettings.mZoomSeekbar.setProgress(0);

        // For now, secure settings will not be updated for values < 1.3f. Follow up on this later.
        verify(mWindowMagnificationSettingsCallback, times(2))
                .onMagnifierScale(mCallbackMagnifierScaleCaptor.capture());
        var capturedArgs = mCallbackMagnifierScaleCaptor.getAllValues();
        assertThat(capturedArgs).hasSize(2);
        assertThat(capturedArgs.get(1)).isWithin(0.01f).of(1f);
    }

    @Test
    public void seekbarProgress_maxProgress_scaleAndCallbackUpdated() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        mWindowMagnificationSettings.showSettingPanel();

        mWindowMagnificationSettings.mZoomSeekbar.setProgress(70);

        verifyScaleUpdatedInSecureSettings(8f);
        verifyCallbackOnMagnifierScale(8f);
    }

    @Test
    public void seekbarProgress_scaleUpdatedAfterSettingPanelOpened_progressAlsoUpdated() {
        setupMagnificationCapabilityAndMode(
                /* capability= */ ACCESSIBILITY_MAGNIFICATION_MODE_ALL,
                /* mode= */ ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        var contentObserverCaptor = ArgumentCaptor.forClass(ContentObserver.class);
        mWindowMagnificationSettings.showSettingPanel();
        verify(mSecureSettings).registerContentObserverForUser(
                eq(ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE),
                contentObserverCaptor.capture(),
                eq(UserHandle.USER_CURRENT));

        // Simulate outside changes.
        setupScaleInSecureSettings(4f);
        // Simulate callback due to outside change.
        contentObserverCaptor.getValue().onChange(/* selfChange= */ false);

        assertThat(mWindowMagnificationSettings.mZoomSeekbar.getProgress()).isEqualTo(30);
    }

    private void verifyScaleUpdatedInSecureSettings(float scale) {
        verify(mSecureSettings).putFloatForUser(
                mSecureSettingsNameCaptor.capture(),
                mSecureSettingsScaleCaptor.capture(),
                mSecureSettingsUserHandleCaptor.capture());
        assertThat(mSecureSettingsScaleCaptor.getValue()).isWithin(0.01f).of(scale);
        assertThat(mSecureSettingsNameCaptor.getValue())
                .isEqualTo(Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE);
        assertThat(mSecureSettingsUserHandleCaptor.getValue()).isEqualTo(UserHandle.USER_CURRENT);
    }

    private void verifyCallbackOnMagnifierScale(float scale) {
        verify(mWindowMagnificationSettingsCallback)
                .onMagnifierScale(mCallbackMagnifierScaleCaptor.capture());
        assertThat(mCallbackMagnifierScaleCaptor.getValue()).isWithin(0.01f).of(scale);
    }

    private <T extends View> T getInternalView(@IdRes int idRes) {
        T view = mSettingView.findViewById(idRes);
        assertNotNull(view);
        return view;
    }

    private void setupMagnificationCapabilityAndMode(int capability, int mode) {
        when(mSecureSettings.getIntForUser(
                eq(Settings.Secure.ACCESSIBILITY_MAGNIFICATION_CAPABILITY),
                anyInt(),
                eq(UserHandle.USER_CURRENT))).thenReturn(capability);
        when(mSecureSettings.getIntForUser(
                eq(Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE),
                anyInt(),
                eq(UserHandle.USER_CURRENT))).thenReturn(mode);
    }

    private void setupScaleInSecureSettings(float scale) {
        when(mSecureSettings.getFloatForUser(
                ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
                MagnificationConstants.SCALE_MIN_VALUE,
                UserHandle.USER_CURRENT)).thenReturn(scale);
    }
}
