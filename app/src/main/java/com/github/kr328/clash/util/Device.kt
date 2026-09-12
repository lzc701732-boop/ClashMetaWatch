package com.github.kr328.clash.util

import android.content.Context
import android.content.res.Configuration

fun Context.isWatchUiMode(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_WATCH
