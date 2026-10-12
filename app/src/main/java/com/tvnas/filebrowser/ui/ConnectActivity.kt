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
import com.tvnas.filebrowser.R
import com.tvnas.filebrowser.databinding.ActivityConnectBinding
import com.tvnas.filebrowser.smb.ServerConfig
import com.tvnas.filebrowser.ui.viewmodel.ConnectViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 添加/编辑 SMB 服务器页面
 */
@AndroidEntryPoint
class ConnectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectBinding
    private val viewModel: ConnectViewModel by viewModels()

    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val serverId = intent.getStringExtra("serverId")
        isEditMode = serverId != null
        viewModel.setEditingServer(serverId)

        setupListeners()
        observeViewModel()

        if (!isEditMode) {
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

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        handleUiState(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is ConnectViewModel.ConnectEvent.FillForm -> fillForm(event.server)
                            is ConnectViewModel.ConnectEvent.NavigateToBrowser -> {
                                val intent = Intent(this@ConnectActivity, BrowserActivity::class.java).apply {
                                    putExtra("serverId", event.serverId)
                                }
                                startActivity(intent)
                                finish()
                            }
                            ConnectViewModel.ConnectEvent.ServerDeleted -> {
                                showToast(getString(R.string.deleted))
                                finish()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleUiState(state: ConnectViewModel.ConnectUiState) {
        when (state) {
            ConnectViewModel.ConnectUiState.Idle -> setLoading(false)
            is ConnectViewModel.ConnectUiState.Loading -> {
                setLoading(true)
                updateStatus(state.message)
            }
            is ConnectViewModel.ConnectUiState.Error -> {
                setLoading(false)
                updateStatus(state.message)
                showToast(state.message)
            }
        }
    }

    private fun fillForm(server: ServerConfig) {
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

        viewModel.connectAndSave(serverAddress, port, shareName, username, password, domain)
    }

    private fun confirmDeleteServer() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_server_title))
            .setMessage(getString(R.string.delete_server_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewModel.deleteServer()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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
