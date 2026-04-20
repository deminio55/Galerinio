package com.example.galerinio.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.galerinio.data.local.GalerioDatabase
import com.example.galerinio.data.repository.CloudAccountRepository
import com.example.galerinio.data.sync.SyncScheduler
import com.example.galerinio.domain.model.CloudAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CloudSyncViewModel(application: Application) : AndroidViewModel(application) {

    private val db = GalerioDatabase.getInstance(application)
    val repository = CloudAccountRepository(db.cloudAccountDao(), db.syncLogDao())

    private val _accounts = MutableStateFlow<List<CloudAccount>>(emptyList())
    val accounts: StateFlow<List<CloudAccount>> = _accounts.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getAllAccountsFlow().collect { list ->
                _accounts.value = list
            }
        }
    }

    fun saveAccount(account: CloudAccount, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repository.saveAccount(account)
            SyncScheduler.scheduleAll(getApplication())
            onDone(id)
        }
    }

    fun deleteAccount(accountId: Long) {
        viewModelScope.launch {
            SyncScheduler.cancelForAccount(getApplication(), accountId)
            repository.deleteAccount(accountId)
            SyncScheduler.scheduleAll(getApplication())
        }
    }

    fun syncNow(accountId: Long) {
        SyncScheduler.syncNow(getApplication(), accountId)
    }

    fun syncAllNow() {
        viewModelScope.launch {
            _isSyncing.value = true
            SyncScheduler.syncAllNow(getApplication())
            _isSyncing.value = false
        }
    }

    fun toggleAccountEnabled(account: CloudAccount) {
        viewModelScope.launch {
            repository.saveAccount(account.copy(isEnabled = !account.isEnabled))
            SyncScheduler.scheduleAll(getApplication())
        }
    }
}

