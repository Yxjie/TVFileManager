package com.tvnas.filebrowser.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvnas.filebrowser.R
import com.tvnas.filebrowser.databinding.ActivityServerListBinding
import com.tvnas.filebrowser.smb.ServerConfig
import com.tvnas.filebrowser.smb.ServerStorage
import com.tvnas.filebrowser.smb.SmbConnectionManager
import com.tvnas.filebrowser.util.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 服务器列表页面
 */
class ServerListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerListBinding
    private lateinit var serverStorage: ServerStorage
    private lateinit var adapter: ServerListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverStorage = ServerStorage(this)
        setupRecyclerView()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        loadServers()
        updateCacheSize()
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

    private fun loadServers() {
        val servers = serverStorage.getServers()
        adapter.submitList(servers)

        if (servers.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
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
                serverStorage.deleteServer(server.id)
                loadServers()
                showToast(getString(R.string.deleted))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun updateCacheSize() {
        val cacheSize = CacheManager.getFormattedCacheSize(this)
        binding.tvCacheSize.text = getString(R.string.cache_label, cacheSize)
    }

    private fun showClearCacheConfirmation() {
        val cacheSize = CacheManager.getFormattedCacheSize(this)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_cache))
            .setMessage(getString(R.string.clear_cache_message, cacheSize))
            .setPositiveButton(getString(R.string.clear_cache)) { _, _ ->
                clearCache()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun clearCache() {
        binding.btnClearCache.isEnabled = false
        binding.btnClearCache.text = getString(R.string.deleting)

        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                CacheManager.clearCache(this@ServerListActivity)
            }

            updateCacheSize()
            binding.btnClearCache.isEnabled = true
            binding.btnClearCache.text = getString(R.string.clear_cache)
            showToast(getString(R.string.cleared_files, count))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 仅在 Activity 真正被销毁（非配置变更）时断开共享连接
        if (isFinishing) {
            SmbConnectionManager.shared.disconnect()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
