package com.example.thesisproject.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.thesisproject.R
import com.example.thesisproject.model.Stop

class StopAdapter(
    private val onClick: (Stop) -> Unit
) : RecyclerView.Adapter<StopAdapter.StopViewHolder>() {

    private val items = mutableListOf<Stop>()

    fun submit(newItems: List<Stop>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StopViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stop, parent, false)
        return StopViewHolder(view)
    }

    override fun onBindViewHolder(holder: StopViewHolder, position: Int) {
        val stop = items[position]
        holder.text.text = stop.name
        holder.itemView.setOnClickListener { onClick(stop) }
    }

    override fun getItemCount(): Int = items.size

    class StopViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.stop_item_text)
    }
}
