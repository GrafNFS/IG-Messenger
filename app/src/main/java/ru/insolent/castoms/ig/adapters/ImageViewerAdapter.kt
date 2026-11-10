package ru.insolent.castoms.ig.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import com.squareup.picasso.Picasso
import ru.insolent.castoms.ig.R
import java.io.File

class ImageViewerAdapter(
    private val imagePaths: List<String>
) : RecyclerView.Adapter<ImageViewerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val photoView: PhotoView = view.findViewById(R.id.photoView)
        val ivPlaceholder: ImageView = view.findViewById(R.id.ivPlaceholder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_viewer_page, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val path = imagePaths[position]
        val file = File(path)

        if (file.exists()) {
            holder.ivPlaceholder.visibility = View.GONE
            holder.photoView.visibility = View.VISIBLE

            Picasso.get()
                .load(file)
                .fit()
                .centerInside()
                .into(holder.photoView)
        } else {
            // Если файл еще не скачался
            holder.photoView.visibility = View.GONE
            holder.ivPlaceholder.visibility = View.VISIBLE
        }
    }

    override fun getItemCount() = imagePaths.size
}