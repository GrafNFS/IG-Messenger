package ru.insolent.castoms.ig.groups

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ru.insolent.castoms.ig.databinding.ItemGroupMemberBinding

/** Участник группы для выбора: чекбокс + имя. Выбор хранится по id — фильтрация безопасна */
class GroupMemberPickerAdapter :
    RecyclerView.Adapter<GroupMemberPickerAdapter.VH>() {

    private var items: List<Pair<String, String>> = emptyList() // id to displayName
    private val selectedIds = mutableSetOf<String>()

    fun submit(list: List<Pair<String, String>>) {
        items = list
        notifyDataSetChanged()
    }

    fun getSelectedIds(): List<String> = selectedIds.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemGroupMemberBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (id, name) = items[position]
        holder.binding.cbMember.text = name
        holder.binding.cbMember.isChecked = selectedIds.contains(id)
        holder.binding.root.setOnClickListener {
            if (!selectedIds.add(id)) selectedIds.remove(id)
            notifyItemChanged(holder.bindingAdapterPosition)
        }
    }

    class VH(val binding: ItemGroupMemberBinding) : RecyclerView.ViewHolder(binding.root)
}
