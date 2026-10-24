// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.util

import android.content.Context
import android.util.Log
import java.io.File
import org.opencv.android.OpenCVLoader

object OpenCVUtils {

    @Volatile private var initialized = false
    @Volatile var lastError: String = "尚未嘗試載入 OpenCV"
        private set

    @Synchronized
    fun init(context: Context): Boolean {
        if (initialized) return true
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val libraryPresent = File(nativeDir, "libopencv_java4.so").exists()
        val environment = "ABI=" + android.os.Build.SUPPORTED_ABIS.joinToString() +
            "；libopencv_java4.so 存在=" + libraryPresent
        try {
            initialized = OpenCVLoader.initDebug()
            lastError = if (initialized) "OpenCV 初始化成功；" + environment
                else "OpenCVLoader.initDebug() 回傳 false；" + environment
            if (!initialized) Log.e("OpenCVUtils", lastError)
        } catch (error: UnsatisfiedLinkError) {
            lastError = "原生函式庫載入失敗：" + error.message + "；" + environment
            Log.e("OpenCVUtils", lastError, error)
        } catch (error: RuntimeException) {
            lastError = "OpenCV 初始化失敗：" + error.message + "；" + environment
            Log.e("OpenCVUtils", lastError, error)
        }
        return initialized
    }
}
