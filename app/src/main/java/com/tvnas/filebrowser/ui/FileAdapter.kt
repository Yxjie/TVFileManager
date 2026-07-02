package com.tvnas.filebrowser.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvnas.filebrowser.R
import com.tvnas.filebrowser.databinding.ItemFileBinding
import com.tvnas.filebrowser.smb.SmbFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class FileAdapter(
    private val thumbnailLoader: ThumbnailLoader?,
    private val lifecycleScope: CoroutineScope,
    private val onItemClick: (SmbFile) -> Unit,
    private val onItemLongClick: (SmbFile) -> Unit
) : ListAdapter<SmbFile, FileAdapter.ViewHolder>(FileDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var loadJob: Job? = null

        fun bind(file: SmbFile) {
            loadJob?.cancel()
            binding.tvName.text = file.name

            val iconRes = getFileIconRes(file)

            if (file.isDecodableImage() && thumbnailLoader != null) {
                loadJob = lifecycleScope.launch {
                    val bitmap = thumbnailLoader.loadThumbnail(file)
                    if (bitmap != null && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        val currentFile = getItemOrNull(bindingAdapterPosition)
                        if (currentFile?.path == file.path) {
                            Glide.with(binding.root.context)
                                .load(bitmap)
                                .placeholder(iconRes)
                                .into(binding.ivIcon)
                        }
                    } else {
                        Glide.with(binding.root.context)
                            .load(iconRes)
                            .into(binding.ivIcon)
                    }
                }
            } else {
                Glide.with(binding.root.context)
                    .load(iconRes)
                    .into(binding.ivIcon)
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val modifiedDate = dateFormat.format(Date(file.lastModified))
            val context = binding.root.context
            binding.tvMeta.text = if (file.isDirectory) {
                context.getString(R.string.meta_folder, modifiedDate)
            } else {
                context.getString(R.string.meta_file, file.formatSize(), modifiedDate)
            }

            binding.tvBadge.visibility = when {
                file.isApk() -> {
                    binding.tvBadge.text = "APK"
                    View.VISIBLE
                }
                file.isMedia() -> {
                    binding.tvBadge.text = file.getExtension().uppercase()
                    View.VISIBLE
                }
                else -> View.GONE
            }

            binding.root.setOnClickListener { onItemClick(file) }
            binding.root.setOnLongClickListener {
                onItemLongClick(file)
                true
            }
        }

        private fun getFileIconRes(file: SmbFile): Int {
            return when {
                file.isDirectory -> R.drawable.ic_folder
                file.isApk() -> R.drawable.ic_apk
                file.isImage() -> R.drawable.ic_image
                file.getExtension() in VIDEO_EXTENSIONS -> R.drawable.ic_video
                file.getExtension() in AUDIO_EXTENSIONS -> R.drawable.ic_music
                file.getExtension() == "pdf" -> R.drawable.ic_pdf
                file.getExtension() in DOC_EXTENSIONS -> R.drawable.ic_document
                file.getExtension() in ARCHIVE_EXTENSIONS -> R.drawable.ic_archive
                file.getExtension() in CODE_EXTENSIONS -> R.drawable.ic_code
                else -> R.drawable.ic_file
            }
        }
    }

    private fun getItemOrNull(position: Int): SmbFile? {
        return if (position in 0 until itemCount) getItem(position) else null
    }

    class FileDiffCallback : DiffUtil.ItemCallback<SmbFile>() {
        override fun areItemsTheSame(oldItem: SmbFile, newItem: SmbFile): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: SmbFile, newItem: SmbFile): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov")
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav")
        private val DOC_EXTENSIONS = setOf("doc", "docx", "txt")
        private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z")
        private val CODE_EXTENSIONS = setOf("exe", "bat", "sh")
    }
}
