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

    private var editingServerId: String? = null
    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverStorage = ServerStorage(this)

        editingServerId = intent.getStringExtra("serverId")
        isEditMode = editingServerId != null

        setupListeners()

        if (isEditMode) {
            loadServerForEdit()
        } else {
            binding.etServerAddress.requestFocus()
        }
    }

    private fun setupListeners() {
        binding.btnConnect.setOnClickListener {
            validateAndConnect()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }

        binding.btnDelete.setOnClickListener {
            confirmDeleteServer()
        }

        binding.btnDelete.visibility = if (isEditMode) View.VISIBLE else View.GONE
    }

    private fun loadServerForEdit() {
        val serverId = editingServerId ?: return
        val server = serverStorage.getServerById(serverId) ?: return

        binding.etServerAddress.setText(server.serverAddress)
        binding.etPort.setText(server.port.toString())
        binding.etShareName.setText(server.shareName)
        binding.etDomain.setText(server.domain)
        binding.etUsername.setText(server.username)
        binding.etPassword.setText(server.password)

        binding.btnConnect.text = getString(R.string.save_and_connect)
    }

    private fun validateAndConnect() {
        val serverAddress = binding.etServerAddress.text.toString().trim()
        val portText = binding.etPort.text.toString().trim()
        val shareName = binding.etShareName.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val domain = binding.etDomain.text.toString().trim()

        // 验证必填项
        if (serverAddress.isEmpty()) {
            binding.tilServerAddress.error = getString(R.string.error_server_address)
            return
        }
        binding.tilServerAddress.error = null

        if (portText.isEmpty()) {
            binding.tilPort.error = getString(R.string.error_port_empty)
            return
        }
        val port = portText.toIntOrNull()
        if (port == null || port < 1 || port > 65535) {
            binding.tilPort.error = getString(R.string.error_port_range)
            return
        }
        binding.tilPort.error = null

        if (shareName.isEmpty()) {
            binding.tilShareName.error = getString(R.string.error_share_name)
            return
        }
        binding.tilShareName.error = null

        if (username.isEmpty()) {
            binding.tilUsername.error = getString(R.string.error_username)
            return
        }
        binding.tilUsername.error = null

        if (password.isEmpty()) {
            binding.tilPassword.error = getString(R.string.error_password)
            return
        }
        binding.tilPassword.error = null

        setLoading(true)
        updateStatus(getString(R.string.connecting_to, serverAddress))

        lifecycleScope.launch {
            val testManager = SmbConnectionManager()
            try {
                withContext(Dispatchers.IO) {
                    testManager.connect(serverAddress, port, shareName, username, password, domain)
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

                // 跳转到浏览器页面（BrowserActivity 会使用共享连接）
                val intent = Intent(this@ConnectActivity, BrowserActivity::class.java).apply {
                    putExtra("serverId", serverConfig.id)
                }
                startActivity(intent)
                finish()

            } catch (e: Exception) {
                updateStatus(getString(R.string.connect_failed_detail, e.message ?: ""))
                showToast(getString(R.string.connect_failed_detail, e.message ?: ""))
            } finally {
                testManager.disconnect()
                setLoading(false)
            }
        }
    }

    private fun confirmDeleteServer() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_server_title))
            .setMessage(getString(R.string.delete_server_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                deleteServer()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteServer() {
        val serverId = editingServerId ?: return
        serverStorage.deleteServer(serverId)
        showToast(getString(R.string.deleted))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        binding.btnConnect.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE

        if (loading) {
            binding.btnConnect.text = getString(R.string.connecting)
        } else {
            binding.btnConnect.text = if (isEditMode)
                getString(R.string.save_and_connect)
            else
                getString(R.string.connect_server)
        }

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
