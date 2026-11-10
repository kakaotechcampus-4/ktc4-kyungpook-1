package com.gitory.backend.profile.domain;

import com.gitory.backend.mail.domain.MailService;
import com.gitory.backend.profile.infra.ProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileMailTest {

    private static final Long USER = 1L;

    private final ProfileRepository profiles = mock(ProfileRepository.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final MailService mails = mock(MailService.class);
    private final ProfileService service = new ProfileService(profiles, transaction, mock(ApplicationEventPublisher.class),
            mails);

    private Profile stored;

    @SuppressWarnings("unchecked")
    private void commitNormally() {

        when(transaction.execute(any())).thenAnswer(call -> ((TransactionCallback<Object>) call.getArgument(0))
                .doInTransaction(mock(TransactionStatus.class)));
        when(profiles.findWithLockByUserId(USER)).thenAnswer(call -> Optional.ofNullable(stored));
        when(profiles.saveAndFlush(any(Profile.class))).thenAnswer(call -> {
            stored = call.getArgument(0);
            return stored;
        });

    }

    private ProfileView save(String email, Boolean mailOptIn) {

        return service.replace(USER, new ProfileInput(List.of(), List.of(), List.of(), List.of(), null, email, mailOptIn));

    }

    private void alreadyStored(String email, boolean mailOptIn) {

        stored = Profile.of(USER);
        stored.replace(List.of(), List.of(), List.of(), List.of(), null);
        stored.changeMail(email, mailOptIn);

    }

    @Test
    @DisplayName("메일 주소는 앞뒤 공백을 떼고 저장하고 빈 글이면 지운다")
    void trimsTheAddressAndClearsItWhenBlank() {

        assertThat(ProfileRules.email("  me@example.com \t")).isEqualTo("me@example.com");
        assertThat(ProfileRules.email("   ")).isEmpty();
        commitNormally();

        assertThat(save("  me@example.com ", false).email()).isEqualTo("me@example.com");
        assertThat(save("   ", false).email()).isNull();

    }

    @ParameterizedTest
    @ValueSource(strings = {"me example.com", "me@@example.com", "me@example", "me.example@com", "a@b@c.com", "@example.com"})
    @DisplayName("메일 주소에 공백이 있거나 @ 가 하나가 아니거나 @ 뒤에 점이 없으면 입력 오류로 거절한다")
    void rejectsAMalformedAddress(String address) {

        assertThatThrownBy(() -> ProfileRules.email(address)).isInstanceOf(InvalidProfileInputException.class);

    }

    @Test
    @DisplayName("메일 주소는 254자까지 받고 255자부터 입력 오류로 거절한다")
    void acceptsUpToTwoHundredFiftyFourCharacters() {

        String at254 = "a".repeat(254 - "@e.com".length()) + "@e.com";

        assertThat(ProfileRules.email(at254)).hasSize(254);
        assertThatThrownBy(() -> ProfileRules.email("a" + at254)).isInstanceOf(InvalidProfileInputException.class);

    }

    @Test
    @DisplayName("수신을 켰는데 저장될 주소가 없으면 입력 오류로 거절한다")
    void rejectsOptInWithoutAnAddress() {

        commitNormally();

        assertThatThrownBy(() -> save(null, true)).isInstanceOf(InvalidProfileInputException.class);
        assertThatThrownBy(() -> save("", true)).isInstanceOf(InvalidProfileInputException.class);
        verify(profiles, never()).saveAndFlush(any());

    }

    @Test
    @DisplayName("주소와 수신 여부를 안 보내면 저장된 값을 그대로 둔다")
    void keepsTheStoredMailSettingsWhenNotSent() {

        commitNormally();
        alreadyStored("me@example.com", true);

        ProfileView saved = save(null, null);

        assertThat(saved.email()).isEqualTo("me@example.com");
        assertThat(saved.mailOptIn()).isTrue();

    }

    @Test
    @DisplayName("한 번도 저장하지 않은 사용자는 주소 null 과 수신 꺼짐으로 보이고 저장하면 보낸 값으로 보인다")
    void viewsTheMailSettingsOfNeverSavedAndSavedUsers() {

        when(profiles.findByUserId(USER)).thenReturn(Optional.empty());
        ProfileView neverSaved = service.of(USER);

        assertThat(neverSaved.email()).isNull();
        assertThat(neverSaved.mailOptIn()).isFalse();

        commitNormally();
        ProfileView saved = save("me@example.com", true);

        assertThat(saved.email()).isEqualTo("me@example.com");
        assertThat(saved.mailOptIn()).isTrue();

    }

    @Test
    @DisplayName("수신을 새로 켜고 저장하면 그 주소로 확인 메일을 한 통 부탁한다")
    void asksForOneConfirmationWhenOptInIsTurnedOn() {

        commitNormally();
        alreadyStored("me@example.com", false);

        save(null, true);

        verify(mails).sendOptInConfirmation("me@example.com");

    }

    @Test
    @DisplayName("수신을 켠 채 주소를 바꿔 저장하면 새 주소로 확인 메일을 부탁한다")
    void asksForConfirmationAtTheNewAddressWhenTheAddressChanges() {

        commitNormally();
        alreadyStored("old@example.com", true);

        save("new@example.com", null);

        verify(mails).sendOptInConfirmation("new@example.com");

    }

    @Test
    @DisplayName("수신이 꺼져 있거나, 켠 채 같은 주소로 다시 저장하면 확인 메일을 부탁하지 않는다")
    void asksForNoConfirmationOtherwise() {

        commitNormally();
        alreadyStored("me@example.com", true);
        save("me@example.com", true);
        alreadyStored("me@example.com", false);
        save("other@example.com", false);

        verify(mails, never()).sendOptInConfirmation(any());

    }

    @Test
    @DisplayName("저장이 롤백되면 확인 메일을 부탁하지 않는다")
    @SuppressWarnings("unchecked")
    void asksForNoConfirmationWhenTheSaveRollsBack() {

        when(profiles.findWithLockByUserId(USER)).thenReturn(Optional.empty());
        when(profiles.saveAndFlush(any(Profile.class))).thenAnswer(call -> call.getArgument(0));
        when(transaction.execute(any())).thenAnswer(call -> {
            ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(mock(TransactionStatus.class));
            throw new IllegalStateException("커밋 실패");
        });

        assertThatThrownBy(() -> save("me@example.com", true)).isInstanceOf(IllegalStateException.class);

        verify(mails, never()).sendOptInConfirmation(any());

    }
}
