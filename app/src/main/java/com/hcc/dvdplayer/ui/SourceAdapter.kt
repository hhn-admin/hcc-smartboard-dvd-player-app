package com.hcc.dvdplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hcc.dvdplayer.databinding.ItemSourceBinding
import com.hcc.dvdplayer.source.ListedSource

class SourceAdapter(
    private val onClick: (ListedSource) -> Unit,
) : RecyclerView.Adapter<SourceAdapter.Holder>() {
    private val items = ArrayList<ListedSource>()

    fun submit(sources: List<ListedSource>) {
        items.clear()
        items.addAll(sources)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemSourceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(source: ListedSource) {
            binding.sourceTitle.text = source.title
            binding.sourceSubtitle.text = source.subtitle
            binding.root.contentDescription = "${source.title}. ${source.subtitle}"
            binding.root.setOnClickListener { onClick(source) }
        }
    }
}
