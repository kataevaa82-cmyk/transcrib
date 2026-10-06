package ru.transcrib.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ru.transcrib.app.BuildConfig

private val licenses = listOf(
    "GigaAM v3 — распознавание речи" to "© 2024 GigaChat Team (SberDevices). MIT.",
    "sherpa-onnx" to "© Xiaomi Corporation. Apache License 2.0.",
    "ONNX Runtime" to "© Microsoft Corporation. MIT.",
    "Silero VAD" to "© Silero Team. MIT.",
    "pyannote segmentation 3.0" to "© 2022 CNRS. MIT.",
    "WeSpeaker ResNet34-LM" to "© WeNet Open Source Community. Apache License 2.0.",
    "Шрифт Onest" to "© 2021 The Onest Project Authors. SIL Open Font License 1.1.",
    "AndroidX, Jetpack Compose, Kotlin" to "© Google LLC, JetBrains s.r.o. Apache License 2.0.",
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    SystemBarsIcons(lightIcons = true)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                .background(Brand.graphiteGradient),
        ) {
            WaveDecoration(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).fillMaxWidth(0.8f).height(46.dp),
                bars = 30,
                alpha = 0.25f,
            )
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(bottom = 36.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    LogoMark(72.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("Транскрибатор", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text(
                        "Версия ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
        }

        Column(Modifier.padding(16.dp)) {
            InfoCard(
                Icons.Filled.Lock,
                "Всё остаётся на телефоне",
                "Распознавание и разделение по спикерам выполняются на устройстве. Приложению не нужен " +
                    "интернет: записи и тексты никуда не отправляются. Нет рекламы и сбора статистики.",
            )
            Spacer(Modifier.height(12.dp))
            InfoCard(
                Icons.Filled.Star,
                "Как получить лучший результат",
                "• Укажите точное число спикеров, если знаете его.\n" +
                    "• Держите телефон ближе к говорящим, без музыки на фоне.\n" +
                    "• Первая обработка после запуска чуть дольше — загружаются модели.\n" +
                    "• Долгие записи можно оставить в фоне: придёт уведомление.\n" +
                    "• На Huawei, Honor и Xiaomi разрешите приложению работу в фоне " +
                    "(Настройки → Батарея → Запуск приложений), иначе система может прервать долгую обработку.",
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Открытые компоненты",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    licenses.forEachIndexed { i, (name, text) ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        Text(name, style = MaterialTheme.typography.labelLarge)
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(16.dp))
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, body: String) {
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brand.coralGradient),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
