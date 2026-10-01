package io.legado.app.ui.video.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.model.VideoPlay
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.widget.components.dialog.AppDialogScaffold
import io.legado.app.ui.widget.components.settings.AppSettingsActionRow
import io.legado.app.ui.widget.components.settings.AppSettingsPanel
import io.legado.app.ui.widget.components.settings.AppSettingsSectionTitle
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.postEvent
import kotlin.math.abs

/**
 * 视频播放器设置弹窗内容。
 *
 * 容器与行都复用项目内的通用组件（[AppDialogScaffold] 顶栏 + [AppSettingsPanel] 分组 +
 * [AppSettingsActionRow] 行，开关放行的尾部插槽），不再手写一套设置行布局。
 */
@Composable
fun VideoSettingsContent(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    //设置项多，用屏幕高度的比例限制弹窗高度，内部滚动
    val maxContentHeight = LocalConfiguration.current.screenHeightDp.dp * 0.8f

    var autoPlay by remember { mutableStateOf(VideoPlay.autoPlay) }
    var startFull by remember { mutableStateOf(VideoPlay.startFull) }
    var fullBottomProgressBar by remember { mutableStateOf(VideoPlay.fullBottomProgressBar) }
    var mutePlay by remember { mutableStateOf(VideoPlay.mutePlay) }
    var playCacheEnabled by remember { mutableStateOf(VideoPlay.playCacheEnabled) }
    var longPressSpeed by remember { mutableIntStateOf(VideoPlay.longPressSpeed) }
    var doubleTapSeekEnabled by remember { mutableStateOf(VideoPlay.doubleTapSeekEnabled) }
    var doubleTapSeekSeconds by remember { mutableIntStateOf(VideoPlay.doubleTapSeekSeconds) }
    var quickJumpButtonsEnabled by remember { mutableStateOf(VideoPlay.quickJumpButtonsEnabled) }
    var quickJumpMinutesA by remember { mutableIntStateOf(VideoPlay.quickJumpMinutesA) }
    var quickJumpMinutesB by remember { mutableIntStateOf(VideoPlay.quickJumpMinutesB) }
    var leftSlideBrightnessEnabled by remember { mutableStateOf(VideoPlay.leftSlideBrightnessEnabled) }
    var rightSlideVolumeEnabled by remember { mutableStateOf(VideoPlay.rightSlideVolumeEnabled) }
    var skipIntroOutroEnabled by remember { mutableStateOf(VideoPlay.skipIntroOutroEnabled) }
    var skipIntroSeconds by remember { mutableIntStateOf(VideoPlay.skipIntroSeconds) }
    var skipOutroSeconds by remember { mutableIntStateOf(VideoPlay.skipOutroSeconds) }

    AppDialogScaffold(
        title = stringResource(R.string.config_settings),
        onDismiss = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxContentHeight),
        navigationContentDescription = stringResource(R.string.close)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppDimens.panelSpacing)
        ) {
            AppSettingsPanel(
                modifier = Modifier.padding(horizontal = AppDimens.panelHorizontalPadding)
            ) {
                AppSettingsSectionTitle(title = stringResource(R.string.video_play_settings))
                val rows = buildList {
                    add(
                        switchSetting(
                            title = stringResource(R.string.auto_play),
                            checked = autoPlay
                        ) {
                            autoPlay = it
                            VideoPlay.autoPlay = it
                        }
                    )
                    if (autoPlay) {
                        add(
                            switchSetting(
                                title = stringResource(R.string.start_full),
                                checked = startFull
                            ) {
                                startFull = it
                                VideoPlay.startFull = it
                            }
                        )
                    }
                    add(
                        switchSetting(
                            title = stringResource(R.string.full_bottom_progress),
                            checked = fullBottomProgressBar
                        ) {
                            fullBottomProgressBar = it
                            VideoPlay.fullBottomProgressBar = it
                        }
                    )
                    add(
                        switchSetting(
                            title = stringResource(R.string.mute_play),
                            checked = mutePlay
                        ) {
                            mutePlay = it
                            VideoPlay.mutePlay = it
                            postEvent(EventBus.VIDEO_CONFIG_CHANGED, true)
                        }
                    )
                    add(
                        clickSetting(
                            title = stringResource(R.string.press_speed),
                            summary = stringResource(
                                R.string.press_speed_summary,
                                longPressSpeed / 10.0f
                            )
                        ) {
                            NumberPickerDialog(context, true)
                                .setTitle(context.getString(R.string.press_speed))
                                .setMaxValue(60)
                                .setMinValue(5)
                                .setValue(longPressSpeed)
                                .setCustomButton(R.string.btn_default_s) {
                                    VideoPlay.longPressSpeed = 30
                                    longPressSpeed = 30
                                }
                                .show { value ->
                                    VideoPlay.longPressSpeed = value
                                    longPressSpeed = value
                                }
                        }
                    )
                    add(
                        switchSetting(
                            title = stringResource(R.string.double_tap_seek_enabled),
                            checked = doubleTapSeekEnabled
                        ) {
                            doubleTapSeekEnabled = it
                            VideoPlay.doubleTapSeekEnabled = it
                        }
                    )
                    if (doubleTapSeekEnabled) {
                        add(
                            clickSetting(
                                title = stringResource(R.string.double_tap_seek_seconds),
                                summary = stringResource(
                                    R.string.double_tap_seek_seconds_summary,
                                    doubleTapSeekSeconds
                                )
                            ) {
                                NumberPickerDialog(context)
                                    .setTitle(context.getString(R.string.double_tap_seek_seconds))
                                    .setMaxValue(60)
                                    .setMinValue(5)
                                    .setValue(doubleTapSeekSeconds)
                                    .setCustomButton(R.string.btn_default_s) {
                                        VideoPlay.doubleTapSeekSeconds = 10
                                        doubleTapSeekSeconds = 10
                                    }
                                    .show { value ->
                                        VideoPlay.doubleTapSeekSeconds = value
                                        doubleTapSeekSeconds = value
                                    }
                            }
                        )
                    }
                    add(
                        switchSetting(
                            title = stringResource(R.string.quick_jump_buttons_enabled),
                            checked = quickJumpButtonsEnabled
                        ) {
                            quickJumpButtonsEnabled = it
                            VideoPlay.quickJumpButtonsEnabled = it
                            postEvent(EventBus.VIDEO_CONFIG_CHANGED, true)
                        }
                    )
                    if (quickJumpButtonsEnabled) {
                        add(
                            clickSetting(
                                title = stringResource(R.string.quick_jump_minutes_a),
                                summary = stringResource(
                                    R.string.quick_jump_minutes_summary,
                                    quickJumpMinutesA
                                )
                            ) {
                                NumberPickerDialog(context)
                                    .setTitle(context.getString(R.string.quick_jump_minutes_a))
                                    .setMaxValue(60)
                                    .setMinValue(1)
                                    .setValue(quickJumpMinutesA)
                                    .setCustomButton(R.string.btn_default_s) {
                                        VideoPlay.quickJumpMinutesA = 5
                                        quickJumpMinutesA = 5
                                        // A 的绝对值不能小于 B
                                        if (abs(quickJumpMinutesB) > 5) {
                                            VideoPlay.quickJumpMinutesB = 5
                                            quickJumpMinutesB = 5
                                        }
                                    }
                                    .show { value ->
                                        VideoPlay.quickJumpMinutesA = value
                                        quickJumpMinutesA = value
                                        if (abs(value) < abs(quickJumpMinutesB)) {
                                            VideoPlay.quickJumpMinutesB = abs(value)
                                            quickJumpMinutesB = abs(value)
                                        }
                                        postEvent(EventBus.VIDEO_CONFIG_CHANGED, true)
                                    }
                            }
                        )
                        add(
                            clickSetting(
                                title = stringResource(R.string.quick_jump_minutes_b),
                                summary = stringResource(
                                    R.string.quick_jump_minutes_summary,
                                    quickJumpMinutesB
                                )
                            ) {
                                NumberPickerDialog(context)
                                    .setTitle(context.getString(R.string.quick_jump_minutes_b))
                                    .setMaxValue(60)
                                    .setMinValue(1)
                                    .setValue(quickJumpMinutesB)
                                    .setCustomButton(R.string.btn_default_s) {
                                        VideoPlay.quickJumpMinutesB = 1
                                        quickJumpMinutesB = 1
                                        if (abs(quickJumpMinutesA) < 1) {
                                            VideoPlay.quickJumpMinutesA = 1
                                            quickJumpMinutesA = 1
                                        }
                                    }
                                    .show { value ->
                                        VideoPlay.quickJumpMinutesB = value
                                        quickJumpMinutesB = value
                                        if (abs(value) > abs(quickJumpMinutesA)) {
                                            VideoPlay.quickJumpMinutesA = abs(value)
                                            quickJumpMinutesA = abs(value)
                                        }
                                        postEvent(EventBus.VIDEO_CONFIG_CHANGED, true)
                                    }
                            }
                        )
                    }
                    add(
                        switchSetting(
                            title = stringResource(R.string.skip_intro_outro_enabled),
                            checked = skipIntroOutroEnabled
                        ) {
                            skipIntroOutroEnabled = it
                            VideoPlay.skipIntroOutroEnabled = it
                            postEvent(EventBus.VIDEO_CONFIG_CHANGED, true)
                        }
                    )
                    if (skipIntroOutroEnabled) {
                        add(
                            clickSetting(
                                title = stringResource(R.string.skip_intro_seconds),
                                summary = stringResource(
                                    R.string.skip_seconds_summary,
                                    skipIntroSeconds
                                )
                            ) {
                                NumberPickerDialog(context)
                                    .setTitle(context.getString(R.string.skip_intro_seconds))
                                    .setMaxValue(300)
                                    .setMinValue(5)
                                    .setValue(skipIntroSeconds)
                                    .setCustomButton(R.string.btn_default_s) {
                                        VideoPlay.skipIntroSeconds = 30
                                        skipIntroSeconds = 30
                                    }
                                    .show { value ->
                                        VideoPlay.skipIntroSeconds = value
                                        skipIntroSeconds = value
                                    }
                            }
                        )
                        add(
                            clickSetting(
                                title = stringResource(R.string.skip_outro_seconds),
                                summary = stringResource(
                                    R.string.skip_seconds_summary,
                                    skipOutroSeconds
                                )
                            ) {
                                NumberPickerDialog(context)
                                    .setTitle(context.getString(R.string.skip_outro_seconds))
                                    .setMaxValue(300)
                                    .setMinValue(5)
                                    .setValue(skipOutroSeconds)
                                    .setCustomButton(R.string.btn_default_s) {
                                        VideoPlay.skipOutroSeconds = 30
                                        skipOutroSeconds = 30
                                    }
                                    .show { value ->
                                        VideoPlay.skipOutroSeconds = value
                                        skipOutroSeconds = value
                                    }
                            }
                        )
                    }
                    add(
                        switchSetting(
                            title = stringResource(R.string.left_slide_brightness_enabled),
                            checked = leftSlideBrightnessEnabled
                        ) {
                            leftSlideBrightnessEnabled = it
                            VideoPlay.leftSlideBrightnessEnabled = it
                        }
                    )
                    add(
                        switchSetting(
                            title = stringResource(R.string.right_slide_volume_enabled),
                            checked = rightSlideVolumeEnabled
                        ) {
                            rightSlideVolumeEnabled = it
                            VideoPlay.rightSlideVolumeEnabled = it
                        }
                    )
                }
                rows.forEachIndexed { index, item ->
                    VideoSettingRow(item = item, showDivider = index != rows.lastIndex)
                }
            }

            Spacer(modifier = Modifier.height(AppDimens.panelSpacing))

            AppSettingsPanel(
                modifier = Modifier.padding(horizontal = AppDimens.panelHorizontalPadding)
            ) {
                AppSettingsSectionTitle(title = stringResource(R.string.video_cache_settings))
                VideoSettingRow(
                    item = switchSetting(
                        title = stringResource(R.string.video_play_cache),
                        checked = playCacheEnabled,
                        summary = stringResource(R.string.video_play_cache_summary)
                    ) {
                        playCacheEnabled = it
                        VideoPlay.playCacheEnabled = it
                    },
                    showDivider = false
                )
            }
        }
    }
}

/**
 * 设置行的数据描述：开关行填 [onCheckedChange]，跳转行填 [onClick]，两者互斥。
 */
private class VideoSettingItem(
    val title: String,
    val summary: String? = null,
    val checked: Boolean = false,
    val onCheckedChange: ((Boolean) -> Unit)? = null,
    val onClick: (() -> Unit)? = null
)

private fun switchSetting(
    title: String,
    checked: Boolean,
    summary: String? = null,
    onCheckedChange: (Boolean) -> Unit
) = VideoSettingItem(
    title = title,
    summary = summary,
    checked = checked,
    onCheckedChange = onCheckedChange
)

private fun clickSetting(
    title: String,
    summary: String? = null,
    onClick: () -> Unit
) = VideoSettingItem(
    title = title,
    summary = summary,
    onClick = onClick
)

@Composable
private fun VideoSettingRow(item: VideoSettingItem, showDivider: Boolean) {
    val onCheckedChange = item.onCheckedChange
    AppSettingsActionRow(
        title = item.title,
        summary = item.summary,
        showDivider = showDivider,
        //开关行点整行等效于点开关，与项目内其它设置页一致
        onClick = {
            item.onClick?.invoke() ?: onCheckedChange?.invoke(!item.checked)
        },
        trailing = if (onCheckedChange == null) {
            null
        } else {
            {
                Switch(
                    checked = item.checked,
                    onCheckedChange = onCheckedChange
                )
            }
        }
    )
}
