package com.otapp.hmis.iam.application;

import com.otapp.hmis.iam.domain.Role;
import com.otapp.hmis.iam.domain.User;
import com.otapp.hmis.iam.domain.UserRepository;
import com.otapp.hmis.iam.lookup.IamLookupService;
import com.otapp.hmis.iam.lookup.UserSummary;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Package-private implementation of the cross-module {@link IamLookupService} (build-spec §5).
 * Returns read projections only; never exposes domain {@code @Entity} references outside iam.
 */
@Service
@RequiredArgsConstructor
class IamLookupServiceImpl implements IamLookupService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSummary> findUser(String uid) {
        return userRepository.findByUid(uid).map(this::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSummary> findUsers(Collection<String> uids) {
        return uids.stream()
                .map(userRepository::findByUid)
                .flatMap(Optional::stream)
                .map(this::toSummary)
                .toList();
    }

    private UserSummary toSummary(User user) {
        String first = user.getFirstName();
        String last  = user.getLastName();
        String displayName;
        if (first != null && last != null) {
            displayName = first.strip() + " " + last.strip();
        } else if (first != null) {
            displayName = first.strip();
        } else if (last != null) {
            displayName = last.strip();
        } else {
            displayName = user.getNickname() != null ? user.getNickname() : user.getUsername();
        }

        List<String> roleNames = user.getRoles().stream()
                .map(Role::getName)
                .sorted()
                .toList();

        return new UserSummary(user.getUid(), user.getUsername(), displayName,
                user.isEnabled(), roleNames);
    }
}
