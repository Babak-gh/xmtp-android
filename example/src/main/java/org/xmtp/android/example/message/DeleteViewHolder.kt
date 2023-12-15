package org.xmtp.android.example.message

import android.graphics.Color
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import org.xmtp.android.example.ClientManager
import org.xmtp.android.example.R
import org.xmtp.android.example.conversation.ConversationDetailViewModel
import org.xmtp.android.example.databinding.ListItemMessageBinding
import org.xmtp.android.example.extension.margins

class DeleteViewHolder(private val binding: ListItemMessageBinding
) : RecyclerView.ViewHolder(binding.root) {


    private val marginLarge = binding.root.resources.getDimensionPixelSize(R.dimen.message_margin)
    private val marginSmall = binding.root.resources.getDimensionPixelSize(R.dimen.padding)
    private val backgroundMe = Color.LTGRAY
    private val backgroundPeer =
        binding.root.resources.getColor(R.color.teal_700, binding.root.context.theme)
    fun bind(
        item: ConversationDetailViewModel.MessageListItem.Delete
    ){
        val isFromMe = ClientManager.client.address == item.message.senderAddress
        val params = binding.messageContainer.layoutParams as ConstraintLayout.LayoutParams
        if (isFromMe) {
            params.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
            params.leftToLeft = ConstraintLayout.LayoutParams.UNSET
            binding.messageRow.margins(left = marginLarge, right = marginSmall)
            binding.messageContainer.setCardBackgroundColor(backgroundMe)
            binding.messageBody.setTextColor(Color.BLACK)
            binding.messageBody.text = "You deleted this message."
        } else {
            params.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
            params.rightToRight = ConstraintLayout.LayoutParams.UNSET
            binding.messageRow.margins(right = marginLarge, left = marginSmall)
            binding.messageContainer.setCardBackgroundColor(backgroundPeer)
            binding.messageBody.setTextColor(Color.WHITE)
            binding.messageBody.text = "This message was deleted."
        }
        binding.messageContainer.layoutParams = params

    }


}