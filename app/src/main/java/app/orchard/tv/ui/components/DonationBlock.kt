package app.orchard.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type

/** The optional tip jar, kept quiet and off the home screen. */
@Composable
fun DonationBlock(modifier: Modifier = Modifier, compact: Boolean = false) {
    val qrSize = if (compact) 82.dp else 124.dp
    val gap = if (compact) 8.dp else 14.dp
    Column(modifier.fillMaxWidth()) {
        BasicText(
            stringResource(R.string.donate_thanks),
            style = if (compact) Type.Headline.copy(color = Color.White)
            else Type.Title.copy(color = Palette.Label),
        )
        Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
        BasicText(
            stringResource(R.string.donate_body),
            style = if (compact) Type.Caption.copy(color = Color(0xFFBFD1E4), lineHeight = 15.sp)
            else Type.Body.copy(color = Palette.SecondaryLabel, lineHeight = 20.sp),
        )
        Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            DonationQr(R.drawable.donate_wechat_qr, stringResource(R.string.donate_wechat), qrSize, compact)
            DonationQr(R.drawable.donate_alipay_qr, stringResource(R.string.donate_alipay), qrSize, compact)
        }
    }
}

@Composable
private fun DonationQr(icon: Int, label: String, size: Dp, compact: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painterResource(icon),
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size).clip(RoundedCornerShape(if (compact) 7.dp else 10.dp))
                .background(Color.White).padding(if (compact) 2.dp else 3.dp),
        )
        Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
        BasicText(
            label,
            style = Type.Caption.copy(
                color = if (compact) Color(0xFFA4BAD1) else Palette.SecondaryLabel,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
