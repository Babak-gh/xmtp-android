package org.xmtp.android.library

import android.util.Log
import io.grpc.StatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.xmtp.android.library.GRPCApiClient.Companion.makeQueryRequest
import org.xmtp.android.library.GRPCApiClient.Companion.makeSubscribeRequest
import org.xmtp.android.library.messages.Envelope
import org.xmtp.android.library.messages.EnvelopeBuilder
import org.xmtp.android.library.messages.InvitationV1
import org.xmtp.android.library.messages.MessageV1Builder
import org.xmtp.android.library.messages.Pagination
import org.xmtp.android.library.messages.SealedInvitation
import org.xmtp.android.library.messages.SealedInvitationBuilder
import org.xmtp.android.library.messages.SignedPublicKeyBundle
import org.xmtp.android.library.messages.Topic
import org.xmtp.android.library.messages.createDeterministic
import org.xmtp.android.library.messages.decrypt
import org.xmtp.android.library.messages.getInvitation
import org.xmtp.android.library.messages.header
import org.xmtp.android.library.messages.involves
import org.xmtp.android.library.messages.recipientAddress
import org.xmtp.android.library.messages.senderAddress
import org.xmtp.android.library.messages.sentAt
import org.xmtp.android.library.messages.toSignedPublicKeyBundle
import org.xmtp.android.library.messages.walletAddress
import org.xmtp.proto.keystore.api.v1.Keystore.TopicMap.TopicData
import org.xmtp.proto.message.contents.Contact
import org.xmtp.proto.message.contents.Invitation
import org.xmtp.android.library.messages.DecryptedMessage
import java.util.Date

data class Conversations(
    var client: Client,
    var conversationsByTopic: MutableMap<String, Conversation> = mutableMapOf(),
) {

    companion object {
        private const val TAG = "CONVERSATIONS"
    }

    /**
     * This method creates a new conversation from an invitation.
     * @param envelope Object that contains the information of the current [Client] such as topic
     * and timestamp.
     * @return [Conversation] from an invitation suing the current [Client].
     */
    fun fromInvite(envelope: Envelope): Conversation {
        val sealedInvitation = Invitation.SealedInvitation.parseFrom(envelope.message)
        val unsealed = sealedInvitation.v1.getInvitation(viewer = client.keys)
        return Conversation.V2(
            ConversationV2.create(
                client = client,
                invitation = unsealed,
                header = sealedInvitation.v1.header,
            ),
        )
    }

    /**
     * This method creates a new conversation from an Intro.
     * @param envelope Object that contains the information of the current [Client] such as topic
     * and timestamp.
     * @return [Conversation] from an Intro suing the current [Client].
     */
    fun fromIntro(envelope: Envelope): Conversation {
        val messageV1 = MessageV1Builder.buildFromBytes(envelope.message.toByteArray())
        val senderAddress = messageV1.header.sender.walletAddress
        val recipientAddress = messageV1.header.recipient.walletAddress
        val peerAddress = if (client.address == senderAddress) recipientAddress else senderAddress
        return Conversation.V1(
            ConversationV1(
                client = client,
                peerAddress = peerAddress,
                sentAt = messageV1.sentAt,
            ),
        )
    }

    /**
     * This creates a new [Conversation] using a specified address
     * @param peerAddress The address of the client that you want to start a new conversation
     * @param context Context of the invitation.
     * @return New [Conversation] using the address and according to that address is able to find
     * the topics if exists for that new conversation.
     */
    fun newConversation(
        peerAddress: String,
        context: Invitation.InvitationV1.Context? = null,
    ): Conversation {
        if (peerAddress.lowercase() == client.address.lowercase()) {
            throw XMTPException("Recipient is sender")
        }
        val existingConversation = conversationsByTopic.values.firstOrNull {
            it.peerAddress == peerAddress && it.conversationId == context?.conversationId
        }
        if (existingConversation != null) {
            return existingConversation
        }
        val contact = client.contacts.find(peerAddress)
            ?: throw XMTPException("Recipient not on network")
        // See if we have an existing v1 convo
        if (context?.conversationId.isNullOrEmpty()) {
            val invitationPeers = listIntroductionPeers()
            val peerSeenAt = invitationPeers[peerAddress]
            if (peerSeenAt != null) {
                val conversation = Conversation.V1(
                    ConversationV1(
                        client = client,
                        peerAddress = peerAddress,
                        sentAt = peerSeenAt,
                    ),
                )
                conversationsByTopic[conversation.topic] = conversation
                return conversation
            }
        }

        // If the contact is v1, start a v1 conversation
        if (Contact.ContactBundle.VersionCase.V1 == contact.versionCase && context?.conversationId.isNullOrEmpty()) {
            val conversation = Conversation.V1(
                ConversationV1(
                    client = client,
                    peerAddress = peerAddress,
                    sentAt = Date(),
                ),
            )
            conversationsByTopic[conversation.topic] = conversation
            return conversation
        }
        // See if we have a v2 conversation
        for (sealedInvitation in listInvitations()) {
            if (!sealedInvitation.involves(contact)) {
                continue
            }
            val invite = sealedInvitation.v1.getInvitation(viewer = client.keys)
            if (invite.context.conversationId == context?.conversationId && invite.context.conversationId != "") {
                val conversation = Conversation.V2(
                    ConversationV2(
                        topic = invite.topic,
                        keyMaterial = invite.aes256GcmHkdfSha256.keyMaterial.toByteArray(),
                        context = invite.context,
                        peerAddress = peerAddress,
                        client = client,
                        header = sealedInvitation.v1.header,
                    ),
                )
                conversationsByTopic[conversation.topic] = conversation
                return conversation
            }
        }
        // We don't have an existing conversation, make a v2 one
        val recipient = contact.toSignedPublicKeyBundle()
        val invitation = Invitation.InvitationV1.newBuilder().build()
            .createDeterministic(client.keys, recipient, context)
        val sealedInvitation =
            sendInvitation(recipient = recipient, invitation = invitation, created = Date())
        val conversationV2 = ConversationV2.create(
            client = client,
            invitation = invitation,
            header = sealedInvitation.v1.header,
        )
        client.contacts.allow(addresses = listOf(peerAddress))
        val conversation = Conversation.V2(conversationV2)
        conversationsByTopic[conversation.topic] = conversation
        return conversation
    }

    /**
     * Get the list of conversations that current user has
     * @return The list of [Conversation] that the current [Client] has.
     */
    fun list(): List<Conversation> {
        val newConversations = mutableListOf<Conversation>()
        val mostRecent = conversationsByTopic.values.maxOfOrNull { it.createdAt }
        val pagination = Pagination(after = mostRecent)
        val seenPeers = listIntroductionPeers(pagination = pagination)
        for ((peerAddress, sentAt) in seenPeers) {
            newConversations.add(
                Conversation.V1(
                    ConversationV1(
                        client = client,
                        peerAddress = peerAddress,
                        sentAt = sentAt,
                    ),
                ),
            )
        }
        val invitations = listInvitations(pagination = pagination)
        for (sealedInvitation in invitations) {
            try {
                newConversations.add(Conversation.V2(conversation(sealedInvitation)))
            } catch (e: Exception) {
                Log.d(TAG, e.message.toString())
            }
        }

        conversationsByTopic += newConversations.filter {
            it.peerAddress != client.address && Topic.isValidTopic(it.topic)
        }.map { Pair(it.topic, it) }

        // TODO(perf): use DB to persist + sort
        return conversationsByTopic.values.sortedByDescending { it.createdAt }
    }

    fun importTopicData(data: TopicData): Conversation {
        val conversation: Conversation
        if (!data.hasInvitation()) {
            val sentAt = Date(data.createdNs / 1_000_000)
            conversation = Conversation.V1(
                ConversationV1(
                    client,
                    data.peerAddress,
                    sentAt,
                ),
            )
        } else {
            conversation = Conversation.V2(
                ConversationV2(
                    topic = data.invitation.topic,
                    keyMaterial = data.invitation.aes256GcmHkdfSha256.keyMaterial.toByteArray(),
                    context = data.invitation.context,
                    peerAddress = data.peerAddress,
                    client = client,
                    createdAtNs = data.createdNs,
                    header = Invitation.SealedInvitationHeaderV1.getDefaultInstance(),
                ),
            )
        }
        conversationsByTopic[conversation.topic] = conversation
        return conversation
    }

    private fun listIntroductionPeers(pagination: Pagination? = null): Map<String, Date> {
        val envelopes =
            runBlocking {
                client.apiClient.queryTopic(
                    topic = Topic.userIntro(client.address),
                    pagination = pagination,
                ).envelopesList
            }
        val messages = envelopes.mapNotNull { envelope ->
            try {
                val message = MessageV1Builder.buildFromBytes(envelope.message.toByteArray())
                // Attempt to decrypt, just to make sure we can
                message.decrypt(client.privateKeyBundleV1)
                message
            } catch (e: Exception) {
                Log.d(TAG, e.message.toString())
                null
            }
        }
        val seenPeers: MutableMap<String, Date> = mutableMapOf()
        for (message in messages) {
            val recipientAddress = message.recipientAddress
            val senderAddress = message.senderAddress
            val sentAt = message.sentAt
            val peerAddress =
                if (recipientAddress == client.address) senderAddress else recipientAddress
            val existing = seenPeers[peerAddress]
            if (existing == null) {
                seenPeers[peerAddress] = sentAt
                continue
            }
            if (existing > sentAt) {
                seenPeers[peerAddress] = sentAt
            }
        }
        return seenPeers
    }

    /**
     * Get the list of invitations using the data sent [pagination]
     * @param pagination Information of the topics, ranges (dates), etc.
     * @return List of [SealedInvitation] that are inside of the range specified by [pagination]
     */
    private fun listInvitations(pagination: Pagination? = null): List<SealedInvitation> {
        val envelopes = runBlocking {
            client.apiClient.envelopes(Topic.userInvite(client.address).description, pagination)
        }
        return envelopes.map { envelope ->
            SealedInvitation.parseFrom(envelope.message)
        }
    }

    fun conversation(sealedInvitation: SealedInvitation): ConversationV2 {
        val unsealed = sealedInvitation.v1.getInvitation(viewer = client.keys)
        return ConversationV2.create(
            client = client,
            invitation = unsealed,
            header = sealedInvitation.v1.header,
        )
    }

    /**
     *  @return This lists messages sent to the [Conversation].
     *  This pulls messages from multiple conversations in a single call.
     *  @see Conversation.messages
     */
    fun listBatchMessages(
        topics: List<Pair<String, Pagination?>>,
    ): List<DecodedMessage> {
        val requests = topics.map { (topic, page) ->
            makeQueryRequest(topic = topic, pagination = page)
        }

        // The maximum number of requests permitted in a single batch call.
        val maxQueryRequestsPerBatch = 50
        val messages: MutableList<DecodedMessage> = mutableListOf()
        val batches = requests.chunked(maxQueryRequestsPerBatch)
        for (batch in batches) {
            runBlocking {
                messages.addAll(
                    client.batchQuery(batch).responsesOrBuilderList.flatMap { res ->
                        res.envelopesList.mapNotNull { envelope ->
                            val conversation = conversationsByTopic[envelope.contentTopic]
                            if (conversation == null) {
                                Log.d(TAG, "discarding message, unknown conversation $envelope")
                                return@mapNotNull null
                            }
                            val msg = conversation.decodeOrNull(envelope)
                            msg
                        }
                    },
                )
            }
        }
        return messages
    }

    /**
     *  @return This lists messages sent to the [Conversation] when the messages are encrypted.
     *  This pulls messages from multiple conversations in a single call.
     *  @see listBatchMessages
     */
    fun listBatchDecryptedMessages(
        topics: List<Pair<String, Pagination?>>,
    ): List<DecryptedMessage> {
        val requests = topics.map { (topic, page) ->
            makeQueryRequest(topic = topic, pagination = page)
        }

        // The maximum number of requests permitted in a single batch call.
        val maxQueryRequestsPerBatch = 50
        val messages: MutableList<DecryptedMessage> = mutableListOf()
        val batches = requests.chunked(maxQueryRequestsPerBatch)
        for (batch in batches) {
            runBlocking {
                messages.addAll(
                    client.batchQuery(batch).responsesOrBuilderList.flatMap { res ->
                        res.envelopesList.mapNotNull { envelope ->
                            val conversation = conversationsByTopic[envelope.contentTopic]
                            if (conversation == null) {
                                Log.d(TAG, "discarding message, unknown conversation $envelope")
                                return@mapNotNull null
                            }
                            try {
                                val msg = conversation.decrypt(envelope)
                                msg
                            } catch (e: Exception) {
                                Log.e(TAG, "Error decrypting message: $envelope", e)
                                null
                            }
                        }
                    },
                )
            }
        }
        return messages
    }

    /**
     * Send an invitation from the current [Client] to the specified recipient (Client)
     * @param recipient The public key of the client that you want to send the invitation
     * @param invitation Invitation object that will be send
     * @param created Specified date creation for this invitation.
     * @return [SealedInvitation] with the specified information.
     */
    fun sendInvitation(
        recipient: SignedPublicKeyBundle,
        invitation: InvitationV1,
        created: Date,
    ): SealedInvitation {
        client.keys.let {
            val sealed = SealedInvitationBuilder.buildFromV1(
                sender = it,
                recipient = recipient,
                created = created,
                invitation = invitation,
            )
            val peerAddress = recipient.walletAddress

            runBlocking {
                client.publish(
                    envelopes = listOf(
                        EnvelopeBuilder.buildFromTopic(
                            topic = Topic.userInvite(
                                client.address,
                            ),
                            timestamp = created,
                            message = sealed.toByteArray(),
                        ),
                        EnvelopeBuilder.buildFromTopic(
                            topic = Topic.userInvite(
                                peerAddress,
                            ),
                            timestamp = created,
                            message = sealed.toByteArray(),
                        ),
                    ),
                )
            }
            return sealed
        }
    }

    /**
     * This subscribes the current [Client] to a topic as userIntro and userInvite and returns a flow
     * of the information of those conversations according to the topics
     * @return Stream of data information for the conversations
     */
    fun stream(): Flow<Conversation> = flow {
        val streamedConversationTopics: MutableSet<String> = mutableSetOf()
        client.subscribeTopic(
            listOf(Topic.userIntro(client.address), Topic.userInvite(client.address)),
        ).collect { envelope ->

            if (envelope.contentTopic == Topic.userIntro(client.address).description) {
                val conversationV1 = fromIntro(envelope = envelope)
                if (!streamedConversationTopics.contains(conversationV1.topic)) {
                    streamedConversationTopics.add(conversationV1.topic)
                    emit(conversationV1)
                }
            }

            if (envelope.contentTopic == Topic.userInvite(client.address).description) {
                val conversationV2 = fromInvite(envelope = envelope)
                if (!streamedConversationTopics.contains(conversationV2.topic)) {
                    streamedConversationTopics.add(conversationV2.topic)
                    emit(conversationV2)
                }
            }
        }
    }

    /**
     * Get the stream of all messages of the current [Client]
     * @return Flow object of [DecodedMessage] that represents all the messages of the
     * current [Client] as userInvite and userIntro
     */
    fun streamAllMessages(): Flow<DecodedMessage> = flow {
        val topics = mutableListOf(
            Topic.userInvite(client.address).description,
            Topic.userIntro(client.address).description,
        )

        for (conversation in list()) {
            topics.add(conversation.topic)
        }

        val subscribeFlow = MutableStateFlow(makeSubscribeRequest(topics))

        while (true) {
            try {
                client.subscribe2(request = subscribeFlow).collect { envelope ->
                    when {
                        conversationsByTopic.containsKey(envelope.contentTopic) -> {
                            val conversation = conversationsByTopic[envelope.contentTopic]
                            val decoded = conversation?.decode(envelope)
                            decoded?.let { emit(it) }
                        }

                        envelope.contentTopic.startsWith("/xmtp/0/invite-") -> {
                            val conversation = fromInvite(envelope = envelope)
                            conversationsByTopic[conversation.topic] = conversation
                            topics.add(conversation.topic)
                            subscribeFlow.value = makeSubscribeRequest(topics)
                        }

                        envelope.contentTopic.startsWith("/xmtp/0/intro-") -> {
                            val conversation = fromIntro(envelope = envelope)
                            conversationsByTopic[conversation.topic] = conversation
                            val decoded = conversation.decode(envelope)
                            emit(decoded)
                            topics.add(conversation.topic)
                            subscribeFlow.value = makeSubscribeRequest(topics)
                        }

                        else -> {}
                    }
                }
            } catch (error: CancellationException) {
                break
            } catch (error: StatusException) {
                if (error.status.code == io.grpc.Status.Code.UNAVAILABLE) {
                    continue
                } else {
                    break
                }
            } catch (error: Exception) {
                continue
            }
        }
    }

    fun streamAllDecryptedMessages(): Flow<DecryptedMessage> = flow {
        val topics = mutableListOf(
            Topic.userInvite(client.address).description,
            Topic.userIntro(client.address).description,
        )

        for (conversation in list()) {
            topics.add(conversation.topic)
        }

        val subscribeFlow = MutableStateFlow(makeSubscribeRequest(topics))

        while (true) {
            try {
                client.subscribe2(request = subscribeFlow).collect { envelope ->
                    when {
                        conversationsByTopic.containsKey(envelope.contentTopic) -> {
                            val conversation = conversationsByTopic[envelope.contentTopic]
                            val decrypted = conversation?.decrypt(envelope)
                            decrypted?.let { emit(it) }
                        }

                        envelope.contentTopic.startsWith("/xmtp/0/invite-") -> {
                            val conversation = fromInvite(envelope = envelope)
                            conversationsByTopic[conversation.topic] = conversation
                            topics.add(conversation.topic)
                            subscribeFlow.value = makeSubscribeRequest(topics)
                        }

                        envelope.contentTopic.startsWith("/xmtp/0/intro-") -> {
                            val conversation = fromIntro(envelope = envelope)
                            conversationsByTopic[conversation.topic] = conversation
                            val decrypted = conversation.decrypt(envelope)
                            emit(decrypted)
                            topics.add(conversation.topic)
                            subscribeFlow.value = makeSubscribeRequest(topics)
                        }

                        else -> {}
                    }
                }
            } catch (error: CancellationException) {
                break
            } catch (error: StatusException) {
                if (error.status.code == io.grpc.Status.Code.UNAVAILABLE) {
                    continue
                } else {
                    break
                }
            } catch (error: Exception) {
                continue
            }
        }
    }
}
