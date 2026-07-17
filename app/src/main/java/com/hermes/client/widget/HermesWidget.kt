package com.hermes.client.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

class HermesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content(context) }
    }

    @Composable
    private fun Content(context: Context) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(ColorProvider(Color(0xFF3B3BAF)))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Item(context, "New chat", "hermes://new")
            Item(context, "Chats", "hermes://tab/sessions")
            Item(context, "Home", "hermes://tab/activity")
        }
    }

    @Composable
    private fun Item(context: Context, label: String, uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(context.packageName)
        Text(
            text = label,
            modifier = GlanceModifier.fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable(actionStartActivity(intent)),
            style = TextStyle(color = ColorProvider(Color.White), fontSize = 16.sp, textAlign = TextAlign.Center),
        )
    }
}
