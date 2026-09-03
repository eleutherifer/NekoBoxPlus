package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.SubscriptionBannerPresentation
import io.nekohasekai.sagernet.ui.SubscriptionExpiration
import io.nekohasekai.sagernet.ui.SubscriptionExpirationUnit
import io.nekohasekai.sagernet.ui.subscriptionExpiration
import io.nekohasekai.sagernet.utils.SubscriptionTrafficFormatter
import kotlinx.coroutines.delay

@Composable
fun SubscriptionBannerCard(
    presentation: SubscriptionBannerPresentation,
    canOpenLinks: Boolean,
    onOpenLinks: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(2.dp)
            .then(if (canOpenLinks) Modifier.clickable(onClick = onOpenLinks) else Modifier),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(
                horizontal = 10.dp,
                vertical = 8.dp,
            ),
        ) {
            if (presentation.hasAnnouncementContent) {
                val announcement = presentation.announcement
                    ?: stringResource(R.string.subscription_provider_announcement)
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = compactBlankLines(announcement),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                lineHeight = 15.sp,
                            ),
                        )
                        presentation.announcementUrl?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.sp,
                                    lineHeight = 15.sp,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(
                        painterResource(R.drawable.ic_baseline_info_24),
                        contentDescription = stringResource(R.string.subscription_provider_announcement),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.padding(3.dp),
                    )
                }
            }

            val showTraffic = presentation.traffic != null &&
                (presentation.showTrafficText || presentation.showTrafficBar)
            if (showTraffic || presentation.expireAt != null) {
                if (presentation.hasAnnouncementContent) {
                    Spacer(
                        Modifier.height(if (presentation.announcementUrl != null) 6.dp else 4.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (presentation.showTrafficBar && presentation.traffic != null) {
                        TrafficBar(
                            progress = presentation.traffic.progress,
                            modifier = Modifier.weight(1f),
                        )
                        if (presentation.showTrafficText || presentation.expireAt != null) {
                            Spacer(Modifier.width(12.dp))
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (presentation.showTrafficText) {
                            presentation.traffic?.let { traffic ->
                                val used = SubscriptionTrafficFormatter.format(
                                    traffic.used,
                                    DataStore.subscriptionTrafficUnit,
                                )
                                Text(
                                    text = traffic.total?.let {
                                        stringResource(
                                            R.string.subscription_traffic_total,
                                            used,
                                            SubscriptionTrafficFormatter.format(
                                                it,
                                                DataStore.subscriptionTrafficUnit,
                                            ),
                                        )
                                    } ?: stringResource(R.string.subscription_used, used),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        lineHeight = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        presentation.expireAt?.let { ExpirationText(it) }
                    }
                }
            }
        }
    }
}

private fun compactBlankLines(value: String) = buildAnnotatedString {
    value.forEachIndexed { index, character ->
        if (character == '\n' && index > 0 && value[index - 1] == '\n') {
            withStyle(SpanStyle(fontSize = 6.5.sp)) { append(character) }
        } else {
            append(character)
        }
    }
}

@Composable
private fun TrafficBar(progress: Int?, modifier: Modifier = Modifier) {
    if (progress != null) {
        LinearProgressIndicator(
            progress = { (progress / 1000f).coerceIn(0f, 1f) },
            modifier = modifier
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
    } else {
        Box(
            modifier
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun ExpirationText(expireAt: Long) {
    var nowMillis by remember(expireAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expireAt) {
        while (true) {
            val expireMillis = if (expireAt > Long.MAX_VALUE / 1000L) {
                Long.MAX_VALUE
            } else {
                expireAt * 1000L
            }
            val remaining = expireMillis - System.currentTimeMillis()
            if (remaining <= 0L) return@LaunchedEffect
            delay((remaining % 60_000L).coerceAtLeast(50L))
            nowMillis = System.currentTimeMillis()
        }
    }
    val expiration = subscriptionExpiration(expireAt, nowMillis)
    val text = when (expiration) {
        SubscriptionExpiration.Expired -> stringResource(R.string.subscription_expiration_expired)
        SubscriptionExpiration.LessThanMinute ->
            stringResource(R.string.subscription_expiration_less_than_minute)
        is SubscriptionExpiration.Remaining -> {
            val resource = when (expiration.unit) {
                SubscriptionExpirationUnit.DAYS -> R.plurals.subscription_expiration_days_left
                SubscriptionExpirationUnit.HOURS -> R.plurals.subscription_expiration_hours_left
                SubscriptionExpirationUnit.MINUTES -> R.plurals.subscription_expiration_minutes_left
            }
            androidx.compose.ui.res.pluralStringResource(
                resource,
                expiration.value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                expiration.value,
            )
        }
    }
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 11.sp,
            lineHeight = 14.sp,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
