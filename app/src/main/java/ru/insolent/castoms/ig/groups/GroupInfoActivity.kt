package ru.insolent.castoms.ig.groups

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import ru.insolent.castoms.ig.databinding.ActivityGroupInfoBinding
import ru.insolent.castoms.ig.databinding.ItemGroupInfoMemberBinding
import ru.insolent.castoms.ig.network.GroupMemberDto
import ru.insolent.castoms.ig.utils.GroupManager
import ru.insolent.castoms.ig.utils.SessionManager

/** «Инфо о группе»: состав с ролями, добавление участников (OWNER), выход из группы */
class GroupInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupInfoBinding
    private lateinit var adapter: MembersAdapter
    private lateinit var groupManager: GroupManager
    private var groupId: String = ""
    private var myRole: String = "MEMBER"
    private var myId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        groupId = intent.getStringExtra("EXTRA_GROUP_ID") ?: run { finish(); return }
        myId = SessionManager(this).getUserId() ?: ""
        groupManager = GroupManager(this)

        adapter = MembersAdapter()
        binding.rvMembers.layoutManager = LinearLayoutManager(this)
        binding.rvMembers.adapter = adapter

        binding.btnAddMembers.setOnClickListener {
            startActivity(Intent(this, AddMembersActivity::class.java)
                .putExtra("EXTRA_GROUP_ID", groupId))
        }

        binding.btnLeave.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Выйти из группы?")
                .setMessage("История переписки останется на сервере, чат исчезнет из списка.")
                .setPositiveButton("Выйти") { _, _ ->
                    lifecycleScope.launch {
                        val ok = groupManager.leaveGroup(groupId, myId)
                        if (ok) {
                            finishAffinity() // на главный экран
                        }
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        load() // после добавления участников список обновится
    }

    private fun load() {
        lifecycleScope.launch {
            val groups = groupManager.refreshMyGroups()
            val group = groups.find { it.id == groupId }
            val members = group?.members ?: groupManager.getMembers(groupId)
            myRole = group?.myRole ?: "MEMBER"

            binding.tvGroupName.text = group?.name ?: "Группа"
            binding.tvMemberCount.text = "${members.size} участников"
            adapter.submit(members.sortedByDescending { it.role == "OWNER" })

            // OWNER может добавлять участников и не может выйти (сервер тоже отвергнет)
            binding.btnAddMembers.visibility =
                if (myRole == "OWNER") android.view.View.VISIBLE else android.view.View.GONE
            binding.btnLeave.visibility =
                if (myRole == "OWNER") android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    class MembersAdapter : RecyclerView.Adapter<MembersAdapter.VH>() {
        private var items: List<GroupMemberDto> = emptyList()

        fun submit(list: List<GroupMemberDto>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemGroupInfoMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = items[position]
            holder.binding.tvName.text = m.displayName ?: m.username ?: m.userId
            holder.binding.tvRole.text = if (m.role == "OWNER") "владелец" else "участник"
        }

        class VH(val binding: ItemGroupInfoMemberBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
