package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import com.fabcontext.api.domain.notification.*;
import com.fabcontext.api.domain.notification.NotificationRepositories.NotificationRepository;
import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationRecoveryApiTest extends ApiTestSupport {
 @Autowired NotificationService notifications;
 @Autowired NotificationStreamHub hub;
 @Autowired TransactionTemplate tx;
 @MockitoSpyBean NotificationRepository repository;
 @Test void databaseFailureClosesConnectionAndReconnectReplaysStoredNotification() throws Exception {
  Session user=cvd(); String uid="USR-CVD-01";
  long base=Long.parseLong(json(getAs(user,"/api/notifications/unread-count"),"$.data.latestEventId"));
  var stream=mvc.perform(get("/api/notifications/stream").session(user.http()).param("lastEventId",String.valueOf(base)))
   .andExpect(request().asyncStarted()).andReturn();
  String source="recovery-"+UUID.randomUUID();
  try {
   doThrow(new org.springframework.dao.DataAccessResourceFailureException("test database unavailable"))
    .when(repository).findByRecipientUserIdAndSeqGreaterThanOrderBySeqAsc(eq(uid),anyLong(),any());
   tx.executeWithoutResult(s->notifications.publish(new NotificationDraft(NotificationType.CHECK_REQUEST_RECEIVED,source,source,null,null,null,null),List.of(uid)));
   for(int i=0;i<200 && hub.subscriberCount(uid)>0;i++) Thread.sleep(25);
   assertThat(hub.subscriberCount(uid)).isZero();
   stream.getAsyncResult(5000); // DB 오류 뒤 연결도 완료되어야 한다.
  } finally { reset(repository); }
  var recovered=mvc.perform(get("/api/notifications/stream").session(user.http()).header("Last-Event-ID",String.valueOf(base)))
   .andExpect(request().asyncStarted()).andReturn();
  String body=recovered.getResponse().getContentAsString(StandardCharsets.UTF_8);
  assertThat(body).contains(source);
  assertThat(body.split("event:notification\n",-1).length-1).isEqualTo(1);
  getAs(user,"/api/notifications").andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
 }
}
