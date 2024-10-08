package org.qbychat.backend.ws;

import com.alibaba.fastjson2.JSON;
import com.google.firebase.messaging.*;
import jakarta.annotation.Resource;
import lombok.extern.log4j.Log4j2;
import org.jetbrains.annotations.NotNull;
import org.qbychat.backend.entity.Account;
import org.qbychat.backend.entity.Group;
import org.qbychat.backend.entity.ChatMessage;
import org.qbychat.backend.service.impl.AccountServiceImpl;
import org.qbychat.backend.service.impl.FriendsServiceImpl;
import org.qbychat.backend.service.impl.GroupsServiceImpl;
import org.qbychat.backend.service.impl.MessageServiceImpl;
import org.qbychat.backend.utils.Const;
import org.qbychat.backend.utils.CryptUtils;
import org.qbychat.backend.utils.QMsgAppContextAware;
import org.qbychat.backend.ws.entity.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
public class QMessengerHandler extends AuthedTextHandler {
    @Resource
    FriendsServiceImpl friendsService;

    @Resource
    AccountServiceImpl accountService;
    @Resource
    GroupsServiceImpl groupsService;
    @Resource
    MessageServiceImpl messageService;

    @Resource
    private RedisTemplate<String, String> stringRedisTemplate;

    @Resource
    QMsgAppContextAware app;

    @Resource
    CryptUtils cryptUtils;

    public static ConcurrentHashMap<Integer, WebSocketSession> connections = new ConcurrentHashMap<>();

    @Override
    protected void afterAuthorization(@NotNull WebSocketSession session, Account account) {
        connections.put(account.getId(), session);
    }

    @Override
    protected void handleTextMessage(@NotNull WebSocketSession session, @NotNull TextMessage message) throws Exception {
        super.handleTextMessage(session, message);
        Request request = JSON.parseObject(message.getPayload(), Request.class);
        String method = request.getMethod();
        Account account = getUser(session);

        switch (method) {
            case RequestType.SEND_MESSAGE -> {
                ChatMessage chatMessage = JSON.parseObject(request.getDataJson(), ChatMessage.class);
                // send message
                // 找到目标并发送
                chatMessage.setSender(account.getId());

                messageService.addMessage(chatMessage);
                // direct message
                if (chatMessage.isDirectMessage() && accountService.hasUser(chatMessage.getTo())) {
                    sendMessage(session, chatMessage.getTo(), chatMessage.clone(), account);
                } else if (!chatMessage.isDirectMessage() && groupsService.hasGroup(chatMessage.getTo())) {
                    Group group = groupsService.findGroupById(chatMessage.getTo());
                    for (Integer memberId : group.getMembers()) {
                        sendMessage(session, memberId, chatMessage.clone(), account);
                    }
                }
            }
            case RequestType.ADD_FRIEND -> {
                RequestAddFriend friendRequest = JSON.parseObject(request.getDataJson(), RequestAddFriend.class);
                Integer target = friendRequest.getTarget();
                friendRequest.setFrom(account.getId());
                if (friendsService.hasFriend(account, accountService.findAccountById(target))) {
                    session.sendMessage(new TextMessage(Response.HAS_FRIEND.toJson()));
                    return;
                }
                // 发送请求
                session.sendMessage(new TextMessage(Response.FRIEND_REQUEST_SENT.toJson()));
                // find target session
                WebSocketSession targetWebsocket = connections.get(target);
                targetWebsocket.sendMessage(new TextMessage(Response.FRIEND_REQUEST.setData(friendRequest).toJson()));
            }
            case RequestType.ACCEPT_FRIEND_REQUEST -> {
                Integer target = JSON.parseObject(request.getDataJson(), Integer.class);
                friendsService.addFriend(getUser(session), accountService.findAccountById(target));
            }
            case RequestType.FETCH_LATEST_MESSAGES -> {
                RequestFetchLatestMessages data = JSON.parseObject(request.getDataJson(), RequestFetchLatestMessages.class);
                List<ChatMessage> messages;
                int channel = data.getChannel();
                if (data.isDirectMessage()) {
                    messages = messageService.fetchLatestDirectMessages(channel, account.getId());
                } else {
                    Group group = groupsService.findGroupById(channel);
                    if (group.getMembers().contains(account.getId())) {
                        messages = messageService.fetchLatestGroupMessages(channel);
                    } else {
                        messages = List.of();
                    }
                }
                for (ChatMessage chatMessage : messages) {
                    ChatMessage.MessageContent content = chatMessage.getContent();
                    content.setText(new String(cryptUtils.decryptString(content.getText())));
                    chatMessage.setContent(content);

                    chatMessage.setSenderInfo(accountService.findAccountById(chatMessage.getSender()));
                    session.sendMessage(chatMessage.toWSTextMessage()); // 排序在客户端进行
                }
            }
        }
    }

    private void sendMessage(@NotNull WebSocketSession session, int to, @NotNull ChatMessage chatMessage, Account account) throws IOException, FirebaseMessagingException, NoSuchPaddingException, IllegalBlockSizeException, NoSuchAlgorithmException, BadPaddingException, InvalidKeyException, CloneNotSupportedException {
        ChatMessage.MessageContent content = chatMessage.getContent();
        content.setText(new String(cryptUtils.decryptString(content.getText())));
        chatMessage.setContent(content);

        FirebaseMessaging firebaseMessaging = app.getBean("firebaseMessaging");;
        chatMessage.setSenderInfo(accountService.findAccountById(chatMessage.getSender()));
        Response msgResponse = chatMessage.toResponse();
        session.sendMessage(new TextMessage(msgResponse.toJson()));
        WebSocketSession targetSession = connections.get(to);
        if (targetSession != null) {
            targetSession.sendMessage(new TextMessage(msgResponse.toJson()));
        }
        String targetFCMToken = stringRedisTemplate.opsForValue().get(Const.FCM_TOKEN + to);
        if (targetFCMToken == null) return;
        firebaseMessaging.send(
                Message.builder()
                        .setToken(targetFCMToken)
                        .setNotification(
                                Notification.builder()
                                        .setTitle(account.getNickname())
                                        .setBody(chatMessage.getContent().getText())
                                        .build()
                        )
                        .build()
        );
    }

    @Override
    public void afterConnectionClosed(@NotNull WebSocketSession session, @NotNull CloseStatus status) throws Exception {
        Account account = getUser(session);
        if (account == null) {
            return; // not authed
        }
        log.info("User {} has disconnected from {}", account.getId(), this.getClass().getName());
        connections.remove(account.getId());
    }
}
