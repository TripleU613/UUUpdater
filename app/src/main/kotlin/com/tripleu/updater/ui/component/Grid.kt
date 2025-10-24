package com.tripleu.updater.ui.component

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyGridScope
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import com.tripleu.updater.prefs.Prefs
import org.koin.androidx.compose.get

@Composable
fun LoadingGrid() {
    @Suppress("DEPRECATION")
    if (get<Prefs>().androidTvUi.get()) {
        TvShimmeringGrid()
    } else {
        ShimmeringList()
    }
}

@Composable
fun ShimmeringList() = LazyColumn(
    modifier = Modifier.fillMaxSize()
) {
    items(6) {
        ShimmerListItem(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
fun ShimmeringGrid() = InstalledGrid(false) {
    items(8) {
        ShimmerGridCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        )
    }
}

@Composable
fun TvShimmeringGrid() = TvInstalledGrid(false) {
    items(8) {
        ShimmerGridCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            imageHeight = 160.dp
        )
    }
}

@Composable
fun EmptyGrid(
    text: String = ""
) = Box(Modifier.fillMaxSize()) {
    if (text.isNotEmpty()) {
        MediumTitle(text, Modifier.align(Alignment.Center))
    }
    LazyColumn(Modifier.fillMaxSize()) {}
}

@Composable
fun InstalledGrid(
    scroll: Boolean = true,
    content: LazyGridScope.() -> Unit
) = LazyVerticalGrid(
    columns =  GridCells.Fixed(getNumColumns(LocalConfiguration.current.orientation)),
    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    content = content,
    userScrollEnabled = scroll,
    modifier = Modifier.fillMaxSize()
)

@Composable
fun TvInstalledGrid(scroll: Boolean = true, content: TvLazyGridScope.() -> Unit) = TvLazyVerticalGrid(
    columns = TvGridCells.Fixed(getTvNumColumns()),
    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    content = content,
    userScrollEnabled = scroll,
    modifier = Modifier.fillMaxSize()
)

@Composable
fun getNumColumns(orientation: Int): Int {
    @Suppress("DEPRECATION")
    val prefs = get<Prefs>()
    return if(orientation == Configuration.ORIENTATION_PORTRAIT)
        prefs.portraitColumns.get()
    else
        prefs.landscapeColumns.get()
}

@Composable
fun getTvNumColumns(): Int {
    return if(LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT)
        1
    else
        2
}

@Composable
private fun ShimmerListItem(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .shimmering(true)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth(0.5f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .shimmering(true)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .shimmering(true)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .shimmering(true)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .shimmering(true)
            )
            Box(
                Modifier
                    .weight(0.6f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .shimmering(true)
            )
        }
    }
}

@Composable
private fun ShimmerGridCard(
    modifier: Modifier = Modifier,
    imageHeight: Dp = 140.dp
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .padding(12.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(imageHeight)
                .clip(RoundedCornerShape(20.dp))
                .shimmering(true)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .height(12.dp)
                .clip(RoundedCornerShape(12.dp))
                .shimmering(true)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth(0.55f)
                .height(10.dp)
                .clip(RoundedCornerShape(12.dp))
                .shimmering(true)
        )
    }
}
