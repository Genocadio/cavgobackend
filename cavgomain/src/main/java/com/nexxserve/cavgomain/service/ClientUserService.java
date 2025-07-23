package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.request.ClientUserRequestDto;
import com.nexxserve.cavgomain.dto.response.AuthResponseDto;
import com.nexxserve.cavgomain.dto.response.ClientUserResponseDto;
import com.nexxserve.cavgomain.entity.ClientUser;
import com.nexxserve.cavgomain.enums.ClientType;
import com.nexxserve.cavgomain.repository.ClientUserRepository;
import com.nexxserve.cavgomain.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ClientUserService {

    private final ClientUserRepository clientUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthResponseDto createClientUser(ClientUserRequestDto requestDto) {
        if (clientUserRepository.findByEmail(requestDto.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }

        ClientUser user = requestDto.toEntity();
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        ClientUser savedUser = clientUserRepository.save(user);

        String accessToken = tokenProvider.generateAccessToken(savedUser);
        String refreshToken = tokenProvider.generateRefreshToken(savedUser);

        return AuthResponseDto.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(savedUser.getId())
                .username(savedUser.getFirstName().concat(" ").concat(savedUser.getLastName()))
                .email(savedUser.getEmail())
                .phone(savedUser.getPhone())
                .userType(savedUser.getClass().getSimpleName())
                .isCompanyUser(false)
                .build();
    }

    public ClientUserResponseDto updateClientUser(Long id, ClientUserRequestDto requestDto) {
        ClientUser existingUser = findEntityById(id);

        existingUser.setFirstName(requestDto.getFirstName());
        existingUser.setLastName(requestDto.getLastName());
        existingUser.setEmail(requestDto.getEmail());
        existingUser.setPhone(requestDto.getPhone());
        existingUser.setClientType(requestDto.getClientType());
        existingUser.setCompanyName(requestDto.getCompanyName());
        existingUser.setPreferredContactMethod(requestDto.getPreferredContactMethod());
        existingUser.setMembershipLevel(requestDto.getMembershipLevel());
        existingUser.setAddress(requestDto.getAddress());
        existingUser.setStatus(requestDto.getStatus());

        ClientUser updatedUser = clientUserRepository.save(existingUser);
        return new ClientUserResponseDto().toDto(updatedUser);
    }

    @Transactional(readOnly = true)
    public ClientUserResponseDto findById(Long id) {
        ClientUser user = findEntityById(id);
        return new ClientUserResponseDto().toDto(user);
    }

    @Transactional(readOnly = true)
    protected ClientUser findEntityById(Long id) {
        return clientUserRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Client user not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public List<ClientUserResponseDto> findAll() {
        return clientUserRepository.findAll().stream()
                .map(user -> new ClientUserResponseDto().toDto(user))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ClientUserResponseDto> findByClientType(ClientType clientType) {
        return clientUserRepository.findByClientType(clientType).stream()
                .map(user -> new ClientUserResponseDto().toDto(user))
                .collect(Collectors.toList());
    }

    public void deleteClientUser(Long id) {
        clientUserRepository.deleteById(id);
    }
}