package ru.insolent.castoms.ig.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import de.hdodenhof.circleimageview.CircleImageView
import ru.insolent.castoms.ig.R
import ru.insolent.castoms.ig.network.FriendRequestDto

class FriendRequestAdapter(
    private var requests: List<FriendRequestDto>,
    private val onAccept: (String) -> Unit,
    private val onReject: (String) -> Unit
) : RecyclerView.Adapter<FriendRequestAdapter.ViewHolder>() {

    fun updateList(newRequests: List<FriendRequestDto>) {
        requests = newRequests
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAvatar: CircleImageView = view.findViewById(R.id.ivAvatar)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val btnAccept: Button = view.findViewById(R.id.btnAccept)
        val btnReject: Button = view.findViewById(R.id.btnReject)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_friend_request, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val req = requests[position]
        val sender = req.sender
        holder.tvName.text = sender.displayName ?: sender.username

        if (sender.avatarUrl != null) {
            Picasso.get().load(sender.avatarUrl).into(holder.ivAvatar)
        }

        holder.btnAccept.setOnClickListener { onAccept(req.id) }
        holder.btnReject.setOnClickListener { onReject(req.id) }
    }

    override fun getItemCount() = requests.size
}