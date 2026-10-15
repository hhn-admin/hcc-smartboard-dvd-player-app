package com.hcc.dvdplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.RecyclerView
import com.hcc.dvdplayer.R
import com.hcc.dvdplayer.databinding.ItemSourceBinding
import com.hcc.dvdplayer.source.ListedSource

class SourceAdapter(
    private val onClick: (ListedSource) -> Unit,
) : RecyclerView.Adapter<SourceAdapter.Holder>() {
    private val items = ArrayList<ListedSource>()
    private var pointedSignature: String? = null

    fun submit(sources: List<ListedSource>, pointed: ListedSource?) {
        items.clear()
        items.addAll(sources)
        pointedSignature = pointed?.signature()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], items[position].signature() == pointedSignature)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.clearArrow()
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemSourceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(source: ListedSource, pointed: Boolean) {
            binding.sourceTitle.text = source.title
            binding.sourceSubtitle.text = source.subtitle
            binding.root.isSelected = pointed
            binding.sourceCard.isSelected = pointed
            val tapHint = if (source.kind == ListedSource.Kind.USB_UVC) {
                binding.root.context.getString(R.string.step_tap)
            } else {
                binding.root.context.getString(R.string.step_tap_only)
            }
            binding.root.contentDescription = if (pointed) {
                "${source.title}. ${source.subtitle}. $tapHint"
            } else {
                "${source.title}. ${source.subtitle}"
            }
            binding.root.setOnClickListener { onClick(source) }
            if (pointed) {
                binding.pointArrow.visibility = View.VISIBLE
                if (binding.pointArrow.animation == null) {
                    binding.pointArrow.startAnimation(
                        AnimationUtils.loadAnimation(binding.root.context, R.anim.arrow_nudge),
                    )
                }
            } else {
                clearArrow()
            }
        }

        fun clearArrow() {
            binding.pointArrow.clearAnimation()
            binding.pointArrow.visibility = View.GONE
        }
    }
}
