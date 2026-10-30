package com.tvnas.filebrowser.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvnas.filebrowser.R
import com.tvnas.filebrowser.databinding.ActivityServerListBinding
import com.tvnas.filebrowser.smb.ServerConfig
import com.tvnas.filebrowser.ui.viewmodel.ServerListViewModel
import com.tvnas.filebrowser.util.FocusHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 服务器列表页面
 */
@AndroidEntryPoint
class ServerListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerListBinding
    private val viewModel: ServerListViewModel by viewModels()
    private lateinit var adapter: ServerListAdapter

    private var lastFocusedServerId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateCacheSize()
        if (::adapter.isInitialized && adapter.itemCount > 0) {
            restoreFocusToSavedServer()
        }
    }

    private fun setupRecyclerView() {
        adapter = ServerListAdapter(
            onItemClick = { server ->
                lastFocusedServerId = server.id
                connectToServer(server)
            },
            onItemLongClick = { server ->
                lastFocusedServerId = server.id
                showServerActionMenu(server)
            },
            onItemFocused = { server -> lastFocusedServerId = server.id }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.btnAddServer.setOnClickListener {
            val intent = Intent(this, ConnectActivity::class.java)
            startActivity(intent)
        }

        binding.btnClearCache.setOnClickListener {
            showClearCacheConfirmation()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.servers.collect { servers ->
                        adapter.submitList(servers) {
                            restoreFocusToSavedServer()
                        }
                        binding.tvEmpty.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
                        binding.recyclerView.visibility = if (servers.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    viewModel.cacheSize.collect { size ->
                        binding.tvCacheSize.text = getString(R.string.cache_label, size)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is ServerListViewModel.UiEvent.ShowToast -> showToast(event.message)
                        }
                    }
                }
            }
        }
    }

    private fun connectToServer(server: ServerConfig) {
        val intent = Intent(this, BrowserActivity::class.java).apply {
            putExtra("serverId", server.id)
        }
        startActivity(intent)
    }

    private fun showServerActionMenu(server: ServerConfig) {
        val options = arrayOf(
            getString(R.string.connect),
            getString(R.string.edit_server),
            getString(R.string.delete)
        )
        AlertDialog.Builder(this)
            .setTitle(server.getDisplayName())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> connectToServer(server)
                    1 -> editServer(server)
                    2 -> confirmDeleteServer(server)
                }
            }
            .show()
    }

    private fun editServer(server: ServerConfig) {
        val intent = Intent(this, ConnectActivity::class.java).apply {
            putExtra("serverId", server.id)
        }
        startActivity(intent)
    }

    private fun confirmDeleteServer(server: ServerConfig) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_server_title))
            .setMessage(getString(R.string.delete_server_confirm, server.getDisplayName()))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                val currentList = adapter.currentList
                val deletedIndex = currentList.indexOfFirst { it.id == server.id }
                if (deletedIndex != -1 && currentList.size > 1) {
                    val nextIndex = if (deletedIndex < currentList.size - 1) deletedIndex + 1 else deletedIndex - 1
                    lastFocusedServerId = currentList[nextIndex].id
                } else {
                    lastFocusedServerId = null
                }
                viewModel.deleteServer(server)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showClearCacheConfirmation() {
        val cacheSize = binding.tvCacheSize.text.toString().substringAfter(": ")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_cache))
            .setMessage(getString(R.string.clear_cache_message, cacheSize))
            .setPositiveButton(getString(R.string.clear_cache)) { _, _ ->
                viewModel.clearCache()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun restoreFocusToSavedServer() {
        if (adapter.itemCount == 0) return
        val targetId = lastFocusedServerId
        val position = if (targetId != null) {
            val pos = adapter.currentList.indexOfFirst { it.id == targetId }
            if (pos != -1) pos else 0
        } else {
            0
        }

        binding.recyclerView.post {
            FocusHelper.restoreAndRequestFocus(binding.recyclerView, position, this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            viewModel.disconnectIfFinishing()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
