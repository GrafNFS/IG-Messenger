package ru.insolent.castoms.ig

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ru.insolent.castoms.ig.databinding.ActivityFolderSettingsBinding

class FolderSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFolderSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация ViewBinding
        binding = ActivityFolderSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Настройка Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        // Обработчик кнопки "Назад"
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        // Обработчик FAB (создание папки)
        binding.fabAddFolder.setOnClickListener {
            showCreateFolderDialog()
        }

        // Инициализация списка папок (заглушка)
        setupFoldersList()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupFoldersList() {
        // Здесь будет RecyclerView с адаптером папок
        // Пока заглушка:
        binding.rvFolders.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        // binding.rvFolders.adapter = FolderAdapter(folders) { folder -> editFolder(folder) }
    }

    private fun showCreateFolderDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("New Folder")

        val input = android.widget.EditText(this)
        input.hint = "Folder name"
        input.setPadding(48, 32, 48, 32)
        builder.setView(input)

        builder.setPositiveButton("Create") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                // TODO: Сохранить новую папку
                // folders.add(Folder(...))
                // adapter.notifyItemInserted(...)
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }
}