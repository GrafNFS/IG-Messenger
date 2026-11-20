package ru.insolent.castoms.ig.groups

import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.insolent.castoms.ig.databinding.ActivityCreateGroupBinding
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.utils.GroupManager

/**
 * Создание группы: имя + множественный выбор контактов.
 * Контакты берём с сервера (GET /api/contacts) — там актуальные displayName.
 */
class CreateGroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateGroupBinding
    private val TAG = "CreateGroupActivity"

    private var contactIds: List<String> = emptyList()
    private var contactNames: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCreate.isEnabled = false
        binding.btnCreate.text = "Загрузка контактов..."

        lifecycleScope.launch {
            try {
                val resp = NetworkClient.apiService.getMyContacts()
                if (resp.isSuccessful && resp.body() != null) {
                    val contacts = resp.body()!!
                    contactIds = contacts.map { it.id }
                    contactNames = contacts.map { it.displayName ?: it.username }

                    binding.listMembers.choiceMode = android.widget.ListView.CHOICE_MODE_MULTIPLE
                    binding.listMembers.adapter = ArrayAdapter(
                        this@CreateGroupActivity,
                        android.R.layout.simple_list_item_multiple_choice,
                        contactNames
                    )
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

        binding.btnCreate.setOnClickListener {
            val name = binding.etGroupName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Введите название группы", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val checked = binding.listMembers.checkedItemPositions
            val selected = mutableListOf<String>()
            for (i in 0 until contactIds.size) {
                if (checked.get(i)) selected.add(contactIds[i])
            }
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
    }
}