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
    }

    private fun setupRecyclerView() {
        adapter = ServerListAdapter(
            onItemClick = { server -> connectToServer(server) },
            onItemEditClick = { server -> editServer(server) },
            onItemDeleteClick = { server -> confirmDeleteServer(server) }
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
                        adapter.submitList(servers)
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
