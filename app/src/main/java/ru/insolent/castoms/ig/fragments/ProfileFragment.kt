package ru.insolent.castoms.ig.fragments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.squareup.picasso.Picasso
import kotlinx.coroutines.launch
import ru.insolent.castoms.ig.FolderSettingsActivity
import ru.insolent.castoms.ig.LoginActivity
import ru.insolent.castoms.ig.R
import ru.insolent.castoms.ig.database.CryptoDatabase
import ru.insolent.castoms.ig.database.UserProfileEntity
import ru.insolent.castoms.ig.databinding.FragmentProfileBinding
import ru.insolent.castoms.ig.network.NetworkClient
import ru.insolent.castoms.ig.utils.SessionManager

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val prefs by lazy { requireContext().getSharedPreferences("ig_messenger_prefs", Context.MODE_PRIVATE) }
    private var isThemeChanging = false
    private lateinit var sessionManager: SessionManager
    private var myId: String = ""
    private val TAG = "ProfileFragment"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())
        myId = sessionManager.getUserId() ?: ""

        setupThemeSelection()
        setupListeners()
        loadProfile()
    }

    private fun loadProfile() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = NetworkClient.apiService.getMyProfile()
                if (response.isSuccessful && response.body() != null) {
                    val dto = response.body()!!
                    val profile = UserProfileEntity(
                        userId = dto.id,
                        username = dto.username,
                        email = dto.email,
                        phone = dto.phone,
                        displayName = dto.displayName,
                        avatarUrl = dto.avatarUrl,
                        status = dto.status
                    )
                    CryptoDatabase.getDatabase(requireContext())
                        .userProfileDao()
                        .saveProfile(profile)
                    displayProfile(profile)
                } else {
                    Log.w(TAG, "Server returned ${response.code()}, loading from DB")
                    loadProfileFromDb()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load profile from server", e)
                loadProfileFromDb()
            }
        }
    }

    private fun loadProfileFromDb() {
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = CryptoDatabase.getDatabase(requireContext()).userProfileDao()
            dao.observeProfile(myId).collect { profile ->
                if (profile != null) {
                    displayProfile(profile)
                } else {
                    binding.tvUsername.text = "Загрузка..."
                    binding.tvDisplayName.text = "..."
                }
            }
        }
    }

    private fun displayProfile(profile: UserProfileEntity) {
        binding.tvUsername.text = "@${profile.username}"
        binding.tvDisplayName.text = profile.displayName ?: profile.username
        binding.tvEmail.text = profile.email ?: "Не указан"
        binding.tvPhone.text = profile.phone ?: "Не указан"

        when (profile.status) {
            "ACTIVE" -> {
                binding.tvAccountStatus.text = "Аккаунт активен"
                binding.tvAccountStatus.setTextColor(requireContext().getColor(R.color.online))
            }
            "SUSPENDED" -> {
                binding.tvAccountStatus.text = "⚠️ Аккаунт заблокирован"
                binding.tvAccountStatus.setTextColor(requireContext().getColor(R.color.primary_red))
            }
            else -> {
                binding.tvAccountStatus.text = "Статус: ${profile.status}"
                binding.tvAccountStatus.setTextColor(requireContext().getColor(R.color.text_primary_light))
            }
        }

        if (!profile.avatarUrl.isNullOrEmpty()) {
            Picasso.get()
                .load(profile.avatarUrl)
                .placeholder(R.drawable.ic_default_avatar)
                .error(R.drawable.ic_default_avatar)
                .into(binding.ivAvatar)
        } else {
            binding.ivAvatar.setImageResource(R.drawable.ic_default_avatar)
        }
    }

    private fun setupThemeSelection() {
        val savedTheme = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedTheme)

        when (savedTheme) {
            AppCompatDelegate.MODE_NIGHT_NO -> binding.radioGroupTheme.check(R.id.radioLight)
            AppCompatDelegate.MODE_NIGHT_YES -> binding.radioGroupTheme.check(R.id.radioDark)
            else -> binding.radioGroupTheme.check(R.id.radioSystem)
        }

        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            if (isThemeChanging) return@setOnCheckedChangeListener
            val newMode = when (checkedId) {
                R.id.radioLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.radioDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            applyTheme(newMode)
        }
    }

    private fun applyTheme(mode: Int) {
        isThemeChanging = true
        prefs.edit().putInt("theme_mode", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
        requireActivity().recreate()
    }

    private fun setupListeners() {
        binding.btnLogout.setOnClickListener {
            NetworkClient.clearToken()
            requireContext().getSharedPreferences("auth_prefs", Context.MODE_PRIVATE).edit().clear().apply()
            val intent = Intent(requireContext(), LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            requireActivity().finishAffinity()
        }

        binding.itemManageFolders.setOnClickListener {
            val intent = Intent(requireContext(), FolderSettingsActivity::class.java)
            startActivity(intent)
        }

        binding.ivAvatar.setOnClickListener {
            Toast.makeText(requireContext(), "Смена аватара (в разработке)", Toast.LENGTH_SHORT).show()
        }

        binding.tvDisplayName.setOnClickListener {
            showEditDisplayNameDialog()
        }

        binding.tvPhone.setOnClickListener {
            showEditPhoneDialog()
        }
    }

    private fun showEditDisplayNameDialog() {
        val currentName = binding.tvDisplayName.text.toString()
        val editText = EditText(requireContext()).apply {
            setText(currentName)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Изменить имя")
            .setView(editText)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    updateProfile(displayName = newName)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showEditPhoneDialog() {
        val currentPhone = binding.tvPhone.text.toString()
            .takeIf { it != "Не указан" } ?: ""
        val editText = EditText(requireContext()).apply {
            setText(currentPhone)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Изменить телефон")
            .setView(editText)
            .setPositiveButton("Сохранить") { _, _ ->
                val newPhone = editText.text.toString().trim()
                updateProfile(phone = newPhone.ifEmpty { null })
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateProfile(
        displayName: String? = null,
        phone: String? = null
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val request = ru.insolent.castoms.ig.network.UpdateProfileRequest(
                    displayName = displayName,
                    phone = phone,
                    avatarUrl = null
                )
                val response = NetworkClient.apiService.updateMyProfile(request)
                if (response.isSuccessful) {
                    Toast.makeText(requireContext(), "Профиль обновлён", Toast.LENGTH_SHORT).show()
                    loadProfile()
                } else {
                    Toast.makeText(requireContext(), "Ошибка обновления", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update profile", e)
                Toast.makeText(requireContext(), "Ошибка сети", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}