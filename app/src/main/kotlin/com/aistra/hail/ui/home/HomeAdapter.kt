package com.aistra.hail.ui.home

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.aistra.hail.app.HailData.tags

class HomeAdapter(fragment: HomeFragment, private val permafrost: Boolean) : FragmentStateAdapter(fragment) {
    override fun getItemCount(): Int = if (permafrost) 1 else tags.size

    override fun createFragment(position: Int): Fragment = PagerFragment.newInstance(permafrost)
}
