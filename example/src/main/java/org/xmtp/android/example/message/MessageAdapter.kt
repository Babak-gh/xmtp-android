package org.xmtp.android.example.message

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.xmtp.android.example.conversation.ConversationDetailViewModel
import org.xmtp.android.example.databinding.ListItemMessageBinding

class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    private val listItems = mutableListOf<ConversationDetailViewModel.MessageListItem>()
    private var onMessageLongClickCallback: OnMessageLongClickCallback? = null


    fun setOnMessageLongClick(onMessageLongClickCallback: OnMessageLongClickCallback){
        this.onMessageLongClickCallback = onMessageLongClickCallback
    }
    fun setData(newItems: List<ConversationDetailViewModel.MessageListItem>) {
        listItems.clear()
        listItems.addAll(newItems)
        newItems.filter {item ->
            item.itemType == ConversationDetailViewModel.MessageListItem.ITEM_TYPE_DELETE
        }.forEach { deletedItem ->
            listItems.removeIf {
                val delete = (deletedItem as ConversationDetailViewModel.MessageListItem.Delete)
                if (it is ConversationDetailViewModel.MessageListItem.Message){
                    it.message.id == delete.message.body
                }else{
                    false
                }
            }
        }
        notifyDataSetChanged()
    }

    fun addItem(item: ConversationDetailViewModel.MessageListItem) {
        when(item.itemType){
            ConversationDetailViewModel.MessageListItem.ITEM_TYPE_MESSAGE -> {
                listItems.add(0, item)
                notifyDataSetChanged()
            }
            ConversationDetailViewModel.MessageListItem.ITEM_TYPE_DELETE -> {
                val delete = (item as ConversationDetailViewModel.MessageListItem.Delete)
                listItems.removeIf {
                    if (it is ConversationDetailViewModel.MessageListItem.Message){
                        it.message.id == delete.message.body
                    }else{
                        false
                    }
                }
                listItems.add(0 , delete)
                notifyDataSetChanged()
            }
        }

    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            ConversationDetailViewModel.MessageListItem.ITEM_TYPE_MESSAGE -> {
                val binding = ListItemMessageBinding.inflate(inflater, parent, false)
                MessageViewHolder(binding)
            }
            ConversationDetailViewModel.MessageListItem.ITEM_TYPE_DELETE -> {
                val binding = ListItemMessageBinding.inflate(inflater, parent, false)
                DeleteViewHolder(binding)
            }
            else -> throw IllegalArgumentException("Unsupported view type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = listItems[position]
        when (holder) {
            is MessageViewHolder -> {
                holder.bind(item as ConversationDetailViewModel.MessageListItem.Message, onMessageLongClickCallback)
            }
            is DeleteViewHolder -> {
                holder.bind(item as ConversationDetailViewModel.MessageListItem.Delete)
            }
            else -> throw IllegalArgumentException("Unsupported view holder")
        }
    }

    override fun getItemViewType(position: Int) = listItems[position].itemType

    override fun getItemCount() = listItems.size

    override fun getItemId(position: Int) = listItems[position].id.hashCode().toLong()

    interface OnMessageLongClickCallback{
        fun deleteMessage(messageId: String)
    }
}
