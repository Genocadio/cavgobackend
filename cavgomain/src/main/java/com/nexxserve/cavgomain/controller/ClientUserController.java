package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.ClientUserRequestDto;
import com.nexxserve.cavgomain.dto.response.ClientUserResponseDto;
import com.nexxserve.cavgomain.enums.ClientType;
import com.nexxserve.cavgomain.service.ClientUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/main/client")
@RequiredArgsConstructor
public class ClientUserController {

    private final ClientUserService clientUserService;

    // Registration removed — users authenticate via Nexxauth, then sync locally

    @PutMapping("/{id}")
    public ClientUserResponseDto updateClientUser(@PathVariable Long id, @Valid @RequestBody ClientUserRequestDto user) {
        return clientUserService.updateClientUser(id, user);
    }

    @GetMapping("/{id}")
    public ClientUserResponseDto getClientUser(@PathVariable Long id) {
        return clientUserService.findById(id);
    }

    @GetMapping
    public List<ClientUserResponseDto> getAllClientUsers() {
        return clientUserService.findAll();
    }

    @GetMapping("/type/{clientType}")
    public List<ClientUserResponseDto> getByClientType(@PathVariable ClientType clientType) {
        return clientUserService.findByClientType(clientType);
    }

    @DeleteMapping("/{id}")
    public void deleteClientUser(@PathVariable Long id) {
        clientUserService.deleteClientUser(id);
    }
}