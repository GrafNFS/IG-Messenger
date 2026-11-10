package ru.insolent.castoms.ig

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import ru.insolent.castoms.ig.databinding.ActivityMainBinding
import ru.insolent.castoms.ig.fragments.ChatsFragment
import ru.insolent.castoms.ig.fragments.ContactsFragment
import ru.insolent.castoms.ig.fragments.ProfileFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Загружаем первый Fragment при старте (только если это не восстановление после поворота)
        if (savedInstanceState == null) {
            loadFragment(ChatsFragment())
        }

        // Обработка переключения табов
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> {
                    loadFragment(ChatsFragment())
                    true
                }
                R.id.nav_contacts -> {
                    loadFragment(ContactsFragment())
                    true
                }
                R.id.nav_profile -> {
                    loadFragment(ProfileFragment())
                    true
                }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    // Правильная обработка кнопки "Назад"
    /*override fun onBackPressed() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (currentFragment !is ChatsFragment) {
            // Если мы не на вкладке "Чаты" — переключаемся на неё
            binding.bottomNavigation.selectedItemId = R.id.nav_chats
        } else {
            // Если уже на "Чатах" — выходим из приложения
            super.onBackPressed()
        }
    }*/
}