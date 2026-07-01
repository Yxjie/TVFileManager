package com.tvnas.filebrowser.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tvnas.filebrowser.R
import com.tvnas.filebrowser.databinding.ActivityConnectBinding
import com.tvnas.filebrowser.smb.ServerConfig
import com.tvnas.filebrowser.smb.ServerStorage
import com.tvnas.filebrowser.smb.SmbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 添加/编辑 SMB 服务器页面
 */
class ConnectActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityConnectBinding
    private lateinit var serverStorage: ServerStorage
    private val smbManager = SmbConnectionManager()
    
    private var editingServerId: String? = null
    private var isEditMode = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        serverStorage = ServerStorage(this)
        
        // 检查是否为编辑模式
        editingServerId = intent.getStringExtra("serverId")
        isEditMode = editingServerId != null
        
        setupListeners()
        
        if (isEditMode) {
            loadServerForEdit()
        } else {
            // 自动聚焦到第一个输入框
            binding.etServerAddress.requestFocus()
        }
    }
    
    private fun setupListeners() {
        binding.btnConnect.setOnClickListener {
            connectToServer()
        }
        
        binding.btnCancel.setOnClickListener {
            finish()
        }
        
        binding.btnDelete.setOnClickListener {
            confirmDeleteServer()
        }
        
        // 编辑模式才显示删除按钮
        binding.btnDelete.visibility = if (isEditMode) View.VISIBLE else View.GONE
    }
    
    private fun loadServerForEdit() {
        val serverId = editingServerId ?: return
        val server = serverStorage.getServerById(serverId) ?: return
        
        // 填充表单
        binding.etServerAddress.setText(server.serverAddress)
        binding.etPort.setText(server.port.toString())
        binding.etShareName.setText(server.shareName)
        binding.etDomain.setText(server.domain)
        binding.etUsername.setText(server.username)
        binding.etPassword.setText(server.password)
        
        // 更新按钮文字
        binding.btnConnect.text = "保存并连接"
    }
    
    private fun connectToServer() {
        val serverAddress = binding.etServerAddress.text.toString().trim()
        val port = binding.etPort.text.toString().toIntOrNull() ?: 445
        val shareName = binding.etShareName.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val domain = binding.etDomain.text.toString().trim()
        
        // 验证必填项
        if (serverAddress.isEmpty()) {
            binding.tilServerAddress.error = "请输入服务器地址"
            return
        }
        binding.tilServerAddress.error = null
        
        if (shareName.isEmpty()) {
            binding.tilShareName.error = "请输入共享名称"
            return
        }
        binding.tilShareName.error = null
        
        if (username.isEmpty()) {
            binding.tilUsername.error = "请输入用户名"
            return
        }
        binding.tilUsername.error = null
        
        if (password.isEmpty()) {
            binding.tilPassword.error = "请输入密码"
            return
        }
        binding.tilPassword.error = null
        
        setLoading(true)
        updateStatus("正在连接 $serverAddress...")
        
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    smbManager.connect(
                        host = serverAddress,
                        port = port,
                        shareName = shareName,
                        username = username,
                        password = password,
                        domain = domain
                    )
                }
                
                // 连接成功，保存配置
                val serverConfig = ServerConfig(
                    id = editingServerId ?: System.currentTimeMillis().toString(),
                    serverAddress = serverAddress,
                    port = port,
                    shareName = shareName,
                    username = username,
                    password = password,
                    domain = domain
                )
                serverStorage.saveServer(serverConfig)
                
                // 跳转到浏览器页面
                val intent = Intent(this@ConnectActivity, BrowserActivity::class.java).apply {
                    putExtra("serverId", serverConfig.id)
                }
                startActivity(intent)
                finish()
                
            } catch (e: Exception) {
                updateStatus("连接失败: ${e.message}")
                showToast("连接失败: ${e.message}")
            } finally {
                setLoading(false)
                smbManager.disconnect()
            }
        }
    }
    
    private fun confirmDeleteServer() {
        AlertDialog.Builder(this)
            .setTitle("删除服务器")
            .setMessage("确定要删除这个服务器配置吗？")
            .setPositiveButton("删除") { _, _ ->
                deleteServer()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun deleteServer() {
        val serverId = editingServerId ?: return
        serverStorage.deleteServer(serverId)
        showToast("服务器已删除")
        finish()
    }
    
    private fun setLoading(loading: Boolean) {
        binding.btnConnect.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        
        if (loading) {
            binding.btnConnect.text = getString(R.string.connecting)
        } else {
            binding.btnConnect.text = if (isEditMode) "保存并连接" else "连接服务器"
        }
        
        // 禁用所有输入框
        binding.etServerAddress.isEnabled = !loading
        binding.etPort.isEnabled = !loading
        binding.etShareName.isEnabled = !loading
        binding.etUsername.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        binding.etDomain.isEnabled = !loading
    }
    
    private fun updateStatus(message: String) {
        binding.tvStatus.text = message
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
