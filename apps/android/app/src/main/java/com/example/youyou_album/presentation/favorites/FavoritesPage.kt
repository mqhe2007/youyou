package com.example.youyou_album.presentation.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.youyou_album.R
import com.example.youyou_album.presentation.widgets.EmptyStateView
import com.example.youyou_album.presentation.widgets.PhotoGrid

/** 已收藏浏览页：独立 Tab，复用时间线网格语言。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesPage(
    onPhotoClick: (String) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("收藏") })
        },
    ) { innerPadding ->
        if (favorites.isEmpty()) {
            EmptyStateView(
                icon = painterResource(R.drawable.lucide_ic_heart),
                title = "还没有收藏",
                message = "在照片详情页点亮心形，收藏的照片会出现在这里",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            PhotoGrid(
                photos = favorites,
                selectedIds = emptySet(),
                selectionMode = false,
                onPhotoClick = onPhotoClick,
                onSelectedIdsChange = {},
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}
