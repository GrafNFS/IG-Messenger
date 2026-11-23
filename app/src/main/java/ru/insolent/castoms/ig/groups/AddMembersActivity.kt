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
import ru.insolent.castoms.ig.databinding.ActivityAddMembersBinding
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.utils.GroupManager

/** Добавление участников в существующую группу (поиск + мультивыбор, текущие скрыты) */
class AddMembersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddMembersBinding
    private lateinit var pickerAdapter: GroupMemberPickerAdapter
    private lateinit var groupManager: GroupManager
    private var groupId: String = ""

    private var allContacts: List<Pair<String, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddMembersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        groupId = intent.getStringExtra("EXTRA_GROUP_ID") ?: run { finish(); return }
        groupManager = GroupManager(this)

        pickerAdapter = GroupMemberPickerAdapter()
        binding.listMembers.layoutManager = LinearLayoutManager(this)
        binding.listMembers.adapter = pickerAdapter

        binding.btnAdd.isEnabled = false
        binding.btnAdd.text = "Загрузка..."

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        binding.btnAdd.setOnClickListener {
            val selected = pickerAdapter.getSelectedIds()
            if (selected.isEmpty()) {
                Toast.makeText(this, "Выберите участников", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnAdd.isEnabled = false
            lifecycleScope.launch {
                val ok = groupManager.addMembers(groupId, selected)
                if (ok) {
                    Toast.makeText(this@AddMembersActivity, "Участники добавлены", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@AddMembersActivity, "Не удалось добавить (владелец? сеть?)", Toast.LENGTH_SHORT).show()
                    binding.btnAdd.isEnabled = true
                }
            }
        }

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val existing = groupManager.getMembers(groupId).map { it.userId }.toSet()
                val resp = NetworkClient.apiService.getMyContacts()
                if (resp.isSuccessful && resp.body() != null) {
                    allContacts = resp.body()!!
                        .filter { it.id !in existing }
                        .map { it.id to (it.displayName ?: it.username) }
                    applyFilter(binding.etSearch.text.toString())
                    binding.btnAdd.isEnabled = true
                    binding.btnAdd.text = "Добавить"
                } else {
                    binding.btnAdd.text = "Ошибка загрузки контактов"
                }
            } catch (e: Exception) {
                Log.e("AddMembersActivity", "load failed", e)
                binding.btnAdd.text = "Нет сети"
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
