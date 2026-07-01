package com.tvnas.filebrowser.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tvnas.filebrowser.databinding.ActivityServerListBinding
import com.tvnas.filebrowser.smb.ServerConfig
import com.tvnas.filebrowser.smb.ServerStorage
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
            .setTitle("删除服务器")
            .setMessage("确定要删除 \"${server.getDisplayName()}\" 吗？")
            .setPositiveButton("删除") { _, _ ->
                serverStorage.deleteServer(server.id)
                loadServers()
                showToast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 更新缓存大小显示
     */
    private fun updateCacheSize() {
        val cacheSize = CacheManager.getFormattedCacheSize(this)
        binding.tvCacheSize.text = "缓存: $cacheSize"
    }
    
    /**
     * 显示清除缓存确认对话框
     */
    private fun showClearCacheConfirmation() {
        val cacheSize = CacheManager.getFormattedCacheSize(this)
        AlertDialog.Builder(this)
            .setTitle("清除缓存")
            .setMessage("确定要清除 $cacheSize 缓存吗？\n\n包括：\n• 图片缩略图\n• APK 安装包")
            .setPositiveButton("清除") { _, _ ->
                clearCache()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 异步清除缓存
     */
    private fun clearCache() {
        binding.btnClearCache.isEnabled = false
        binding.btnClearCache.text = "清除中..."
        
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                CacheManager.clearCache(this@ServerListActivity)
            }
            
            updateCacheSize()
            binding.btnClearCache.isEnabled = true
            binding.btnClearCache.text = "清除缓存"
            showToast("已清除 $count 个文件")
        }
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
