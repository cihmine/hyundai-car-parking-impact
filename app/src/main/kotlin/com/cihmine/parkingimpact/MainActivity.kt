package com.cihmine.parkingimpact

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.cihmine.parkingimpact.ui.ParkingApp

class MainActivity : ComponentActivity() {
    private val controller: ParkingController by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 차량 화면(가로 1200dp 이상, 저밀도)은 같은 dp 로는 글씨가 작아 전체를 키운다.
            val base = LocalDensity.current
            val scale = if (LocalConfiguration.current.screenWidthDp >= 1200) 1.5f else 1f
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    ParkingApp(controller)
                }
            }
        }
    }
}
