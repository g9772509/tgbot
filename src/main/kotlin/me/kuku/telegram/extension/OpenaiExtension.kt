package me.kuku.telegram.extension

import com.aallam.openai.api.chat.*
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.fasterxml.jackson.databind.JsonNode
import com.pengrad.telegrambot.model.PhotoSize
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.EditMessageText
import com.pengrad.telegrambot.request.GetFile
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import me.kuku.telegram.context.AbilitySubscriber
import me.kuku.telegram.context.Locality
import me.kuku.telegram.context.asyncExecute
import me.kuku.telegram.context.byteArray
import me.kuku.telegram.entity.BotConfigService
import me.kuku.utils.Jackson
import me.kuku.utils.base64Encode
import me.kuku.utils.client
import me.kuku.utils.setJsonBody
import org.springframework.stereotype.Component

@Component
class OpenaiExtension(
    private val botConfigService: BotConfigService
) {

    fun AbilitySubscriber.openai() {

        sub(name = "chat", locality = Locality.ALL) {
            val replyToMessage = message.replyToMessage()
            var text = ""
            val photoList = mutableListOf<String>()
            val photoSizeList: Array<PhotoSize>?
            if (replyToMessage != null) {
                text = (replyToMessage.text() ?: "") + message.text()
                photoSizeList = replyToMessage.photo()
            } else {
                text = firstArg()
                photoSizeList = message.photo()
            }
            photoSizeList?.groupBy { it.fileUniqueId().dropLast(1) }?.mapNotNull { (_, group) -> group.maxByOrNull { it.fileSize() } }
                ?.forEach { photoSize ->
                val getFile = GetFile(photoSize.fileId())
                val getFileResponse = bot.asyncExecute(getFile)
                val base64 = getFileResponse.file().byteArray().base64Encode()
                photoList.add(base64)
            }
            val botConfigEntity = botConfigService.find()
            if (botConfigEntity.openaiToken.ifEmpty { "" }.isEmpty()) error("not setting openai token")

            val openai = OpenAI(botConfigEntity.openaiToken)

            val request = ChatCompletionRequest(
                model = ModelId("gpt-4o-mini"),
                messages = listOf(
                    ChatMessage(
                        role = ChatRole.User,
                        content = ContentPartBuilder().also {
                            it.text(text)
                            for (photo in photoList) {
                                it.image("data:image/jpeg;base64,$photo")
                            }
                        }.build()
                    )
                ),
                streamOptions = streamOptions {
                    includeUsage = true
                }
            )

            runBlocking {
                val response = sendMessage("Processing\\.\\.\\.", parseMode = ParseMode.MarkdownV2, replyToMessageId = message.messageId())
                val sendMessageObject = response.message()
                val sendMessageId = sendMessageObject.messageId()
                var openaiText = ""
                var prefix = ">model: gpt\\-4o\\-mini\n"
                var alreadySendText = ""
                var i = 5
                openai.chatCompletions(request).onEach {
                    it.choices.getOrNull(0)?.delta?.content?.let { content ->
                        openaiText += content
                    }
                    it.usage?.let { usage ->
                        prefix += ">promptToken: ${usage.promptTokens}\n>completionToken: ${usage.completionTokens}\n"
                    }
                    if (i++ % 20 == 0) {
                        val sendText = "$prefix\n```text\n$openaiText```"
                        if (alreadySendText != sendText) {
                            alreadySendText = sendText
                            val editMessageText = EditMessageText(chatId, sendMessageId, sendText)
                                .parseMode(ParseMode.MarkdownV2)
                            bot.asyncExecute(editMessageText)
                        }
                    }
                }.onCompletion {
                    val sendText = "$prefix\n```text\n$openaiText```"
                    if (alreadySendText != sendText) {
                        alreadySendText = sendText
                        val editMessageText = EditMessageText(chatId, sendMessageId, sendText)
                            .parseMode(ParseMode.MarkdownV2)
                        bot.asyncExecute(editMessageText)
                    }
                }.launchIn(this).join()
            }
        }


    }

}