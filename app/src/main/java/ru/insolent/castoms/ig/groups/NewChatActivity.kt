package ru.insolent.castoms.ig.groups

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.insolent.castoms.ig.ChatActivity
import ru.insolent.castoms.ig.databinding.ActivityNewChatBinding
import ru.insolent.castoms.ig.network.NetworkClient

/**
 * «Новый чат»: поиск по контактам + кнопка создания группы.
 * Тап по контакту — открывает личный чат.
 */
class NewChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewChatBinding
    private val TAG = "NewChatActivity"

    private var allIds: List<String> = emptyList()
    private var allNames: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnCreateGroup.setOnClickListener {
            startActivity(Intent(this, CreateGroupActivity::class.java))
        }

        binding.listContacts.divider = null

        binding.listContacts.setOnItemClickListener { _, _, position, _ ->
            val adapter = binding.listContacts.adapter as ArrayAdapter<String>
            val name = adapter.getItem(position) ?: return@setOnItemClickListener
            // Ищем id по отображаемому имени (в рамках текущего отфильтрованного списка)
            val index = currentFilteredIndex(position)
            if (index != -1) {
                val intent = Intent(this, ChatActivity::class.java).apply {
                    putExtra("EXTRA_CONTACT_ID", allIds[index])
                    putExtra("EXTRA_CONTACT_NAME", name)
                    putExtra("EXTRA_IS_GROUP", false)
                }
                startActivity(intent)
                finish()
            }
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        loadContacts()
    }

    /** Индекс в полном списке по позиции в отфильтрованном адаптере */
    private var filteredIndices: List<Int> = emptyList()

    private fun currentFilteredIndex(adapterPosition: Int): Int =
        filteredIndices.getOrElse(adapterPosition) { -1 }

    private fun loadContacts() {
        lifecycleScope.launch {
            try {
                val resp = NetworkClient.apiService.getMyContacts()
                if (resp.isSuccessful && resp.body() != null) {
                    val contacts = resp.body()!!
                    allIds = contacts.map { it.id }
                    allNames = contacts.map { it.displayName ?: it.username }
                    applyFilter(binding.etSearch.text.toString())
                } else {
                    Toast.makeText(this@NewChatActivity, "Не удалось загрузить контакты", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "load contacts failed", e)
                Toast.makeText(this@NewChatActivity, "Нет сети", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        filteredIndices = if (q.isEmpty()) {
            allNames.indices.toList()
        } else {
            allNames.indices.filter { allNames[it].lowercase().contains(q) }
        }
        val shown = filteredIndices.map { allNames[it] }
        binding.listContacts.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, shown
        )
    }
}
