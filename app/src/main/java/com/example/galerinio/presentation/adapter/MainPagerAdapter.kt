package com.example.galerinio.presentation.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.galerinio.R
import com.example.galerinio.presentation.ui.fragment.AlbumsFragment
import com.example.galerinio.presentation.ui.fragment.GalleryFragment

class MainPagerAdapter(
    fa: FragmentActivity,
    private val filtersOrder: List<Int>
) : FragmentStateAdapter(fa) {

    override fun getItemCount(): Int = filtersOrder.size.coerceAtLeast(1)

    override fun createFragment(position: Int): Fragment = when (filterIdAt(position)) {
        R.id.filterPhotos -> GalleryFragment.newInstance(GalleryFragment.GalleryFilter.PHOTOS)
        R.id.filterVideos -> GalleryFragment.newInstance(GalleryFragment.GalleryFilter.VIDEOS)
        R.id.filterFolders -> AlbumsFragment()
        R.id.filterFavorites -> GalleryFragment.newInstance(GalleryFragment.GalleryFilter.FAVORITES)
        else -> GalleryFragment.newInstance(GalleryFragment.GalleryFilter.ALL)
    }

    fun filterIdAt(position: Int): Int {
        return filtersOrder.getOrElse(position) { R.id.filterAllFiles }
    }

    fun pageOf(filterId: Int): Int {
        return filtersOrder.indexOf(filterId).takeIf { it >= 0 } ?: 0
    }
}

