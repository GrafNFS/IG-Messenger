package ru.insolent.castoms.ig.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ru.insolent.castoms.ig.R
import ru.insolent.castoms.ig.network.UserSearchResult

class SearchUserAdapter(
    private val onUserSelected: (UserSearchResult) -> Unit
) : RecyclerView.Adapter<SearchUserAdapter.ViewHolder>() {

    private var users: List<UserSearchResult> = emptyList()

    fun updateList(newUsers: List<UserSearchResult>) {
        users = newUsers
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvUserName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_user, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = users[position]
        holder.tvName.text = user.displayName ?: user.username
        holder.itemView.setOnClickListener { onUserSelected(user) }
    }

    override fun getItemCount() = users.size
}