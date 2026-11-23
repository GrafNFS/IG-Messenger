package ru.insolent.castoms.ig.groups

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import ru.insolent.castoms.ig.databinding.ActivityCreateGroupBinding
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.utils.GroupManager

/**
 * Создание группы: имя + поиск + множественный выбор участников.
 */
class CreateGroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateGroupBinding
    private lateinit var pickerAdapter: GroupMemberPickerAdapter
    private val TAG = "CreateGroupActivity"

    private var allContacts: List<Pair<String, String>> = emptyList() // id to name

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        pickerAdapter = GroupMemberPickerAdapter()
        binding.listMembers.layoutManager = LinearLayoutManager(this)
        binding.listMembers.adapter = pickerAdapter

        binding.btnCreate.isEnabled = false
        binding.btnCreate.text = "Загрузка контактов..."

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        binding.btnCreate.setOnClickListener {
            val name = binding.etGroupName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Введите название группы", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selected = pickerAdapter.getSelectedIds()
            if (selected.isEmpty()) {
                Toast.makeText(this, "Выберите хотя бы одного участника", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnCreate.isEnabled = false
            lifecycleScope.launch {
                val group = GroupManager(this@CreateGroupActivity).createGroup(name, selected)
                if (group != null) {
                    Toast.makeText(this@CreateGroupActivity, "Группа создана", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@CreateGroupActivity, "Не удалось создать группу", Toast.LENGTH_SHORT).show()
                    binding.btnCreate.isEnabled = true
                }
            }
        }

        loadContacts()
    }

    private fun loadContacts() {
        lifecycleScope.launch {
            try {
                val resp = NetworkClient.apiService.getMyContacts()
                if (resp.isSuccessful && resp.body() != null) {
                    val contacts = resp.body()!!
                    allContacts = contacts.map { it.id to (it.displayName ?: it.username) }
                    applyFilter(binding.etSearch.text.toString())
                    binding.btnCreate.isEnabled = true
                    binding.btnCreate.text = "Создать группу"
                } else {
                    binding.btnCreate.text = "Ошибка загрузки контактов"
                }
            } catch (e: Exception) {
                Log.e(TAG, "load contacts failed", e)
                binding.btnCreate.text = "Нет сети"
            }
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) allContacts
        else allContacts.filter { it.second.lowercase().contains(q) }
        pickerAdapter.submit(filtered)
    }
}
