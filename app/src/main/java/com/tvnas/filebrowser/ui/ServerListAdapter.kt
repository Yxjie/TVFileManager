package com.tvnas.filebrowser.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvnas.filebrowser.R
import com.tvnas.filebrowser.databinding.ItemServerBinding
import com.tvnas.filebrowser.smb.ServerConfig

/**
 * 服务器列表适配器
 */
class ServerListAdapter(
    private val onItemClick: (ServerConfig) -> Unit,
    private val onItemLongClick: (ServerConfig) -> Unit,
    private val onItemFocused: ((ServerConfig) -> Unit)? = null
) : ListAdapter<ServerConfig, ServerListAdapter.ViewHolder>(ServerDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class ViewHolder(
        private val binding: ItemServerBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(server: ServerConfig) {
            binding.tvName.text = server.getDisplayName()
            binding.tvAddress.text = server.getFullAddress()
            binding.tvUsername.text = binding.root.context.getString(R.string.user_label, server.username)
            
            binding.root.setOnClickListener {
                onItemClick(server)
            }

            binding.root.setOnLongClickListener {
                onItemLongClick(server)
                true
            }

            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    getItemOrNull(bindingAdapterPosition)?.let { currentServer ->
                        onItemFocused?.invoke(currentServer)
                    }
                }
            }
        }
    }

    private fun getItemOrNull(position: Int): ServerConfig? {
        return if (position in 0 until itemCount) getItem(position) else null
    }
    
    class ServerDiffCallback : DiffUtil.ItemCallback<ServerConfig>() {
        override fun areItemsTheSame(oldItem: ServerConfig, newItem: ServerConfig): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: ServerConfig, newItem: ServerConfig): Boolean {
            return oldItem == newItem
        }
    }
}
