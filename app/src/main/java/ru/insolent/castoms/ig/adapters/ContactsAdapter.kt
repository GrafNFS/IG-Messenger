package ru.insolent.castoms.ig.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import de.hdodenhof.circleimageview.CircleImageView
import ru.insolent.castoms.ig.R
import ru.insolent.castoms.ig.models.Contact

class ContactsAdapter(
    var contacts: List<Contact>,
    private val onItemClick: (Contact) -> Unit,
    private val onAddClick: (Contact) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {

    fun updateList(newContacts: List<Contact>) {
        contacts = newContacts
        notifyDataSetChanged()
    }

    // ✅ Метод для мгновенного обновления после отправки заявки
    fun markAsRequested(contactId: String) {
        val index = contacts.indexOfFirst { it.id == contactId }
        if (index != -1) {
            val updatedContact = contacts[index].copy(isRequested = true)
            contacts = contacts.toMutableList().apply { this[index] = updatedContact }
            notifyItemChanged(index)
        }
    }

    // ✅ Метод для мгновенного обновления после принятия заявки (когда человек стал контактом)
    fun markAsContact(contactId: String) {
        val index = contacts.indexOfFirst { it.id == contactId }
        if (index != -1) {
            val updatedContact = contacts[index].copy(isMyContact = true, isRequested = false, status = "Tap to chat")
            contacts = contacts.toMutableList().apply { this[index] = updatedContact }
            notifyItemChanged(index)
        }
    }

    class ContactViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAvatar: CircleImageView = view.findViewById(R.id.ivContactAvatar)
        val viewOnline: View = view.findViewById(R.id.viewOnlineIndicator)
        val tvName: TextView = view.findViewById(R.id.tvContactName)
        val tvStatus: TextView = view.findViewById(R.id.tvContactStatus)
        val btnAdd: ImageButton = view.findViewById(R.id.btnAddContact)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]
        holder.tvName.text = contact.name
        holder.tvStatus.text = contact.status
        holder.viewOnline.isVisible = contact.isOnline

        if (contact.avatarUrl != null) {
            Picasso.get().load(contact.avatarUrl).into(holder.ivAvatar)
        } else {
            holder.ivAvatar.setImageResource(contact.avatarResId)
        }

        // ✅ Три состояния элемента
        if (contact.isMyContact) {
            // Это мой контакт: скрываем кнопку, клик по всему элементу открывает чат
            holder.btnAdd.isVisible = false
            holder.itemView.setOnClickListener { onItemClick(contact) }
        } else if (contact.isRequested) {
            // Заявка уже отправлена: показываем иконку "отправлено", кнопка неактивна
            holder.btnAdd.isVisible = true
            holder.btnAdd.setImageResource(android.R.drawable.ic_menu_send)
            holder.btnAdd.isEnabled = false
            holder.btnAdd.alpha = 0.5f
            holder.itemView.setOnClickListener { onItemClick(contact) }
        } else {
            // Не в контактах и заявка не отправлена: показываем кнопку "+"
            holder.btnAdd.isVisible = true
            holder.btnAdd.setImageResource(android.R.drawable.ic_input_add)
            holder.btnAdd.isEnabled = true
            holder.btnAdd.alpha = 1.0f
            holder.btnAdd.setOnClickListener { onAddClick(contact) }
            holder.itemView.setOnClickListener { onItemClick(contact) }
        }
    }

    override fun getItemCount(): Int = contacts.size
}