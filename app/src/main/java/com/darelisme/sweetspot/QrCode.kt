package com.darelisme.sweetspot

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Renders text as a QR bitmap. Thin wrapper so UI code stays ZXing-free. */
object QrCode {

    fun generate(text: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        val dark = Color.WHITE // inverted: light modules on dark panel
        val light = Color.TRANSPARENT
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) dark else light)
            }
        }
        return bmp
    }
}
