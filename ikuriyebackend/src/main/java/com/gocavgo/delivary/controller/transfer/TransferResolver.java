package com.gocavgo.delivary.controller.transfer;

import com.gocavgo.delivary.service.transfer.TransferService;
import com.gocavgo.delivary.enums.transfer.TransferStatus;
import com.gocavgo.delivary.dto.delivery.input.AcceptTransferInput;
import com.gocavgo.delivary.dto.delivery.output.TransferAcceptResult;
import com.gocavgo.delivary.dto.transfer.input.AddPackagesToTransferInput;
import com.gocavgo.delivary.dto.transfer.input.CreateTransferInput;
import com.gocavgo.delivary.dto.transfer.input.RegenerateTransferCodeInput;
import com.gocavgo.delivary.dto.transfer.input.UpdateTransferInput;
import com.gocavgo.delivary.dto.transfer.output.TransferResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class TransferResolver {

    private final TransferService transferService;

    // ──────────────────────────────────────────────
    // Queries
    // ──────────────────────────────────────────────

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public TransferResponse transfer(@Argument UUID id) {
        return transferService.getTransferById(id);
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public List<TransferResponse> myTransfers() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var userId = Long.parseLong(authentication.getName());
        return transferService.getTransfersByCreator(userId);
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public List<TransferResponse> transfersByStatus(@Argument TransferStatus status) {
        return transferService.getTransfersByStatus(status);
    }

    // ──────────────────────────────────────────────
    // Mutations
    // ──────────────────────────────────────────────

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public TransferResponse createTransfer(@Argument @Valid CreateTransferInput input) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var creatorId = Long.parseLong(authentication.getName());
        return transferService.createTransfer(creatorId, input);
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public TransferResponse addPackagesToTransfer(@Argument @Valid AddPackagesToTransferInput input) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var actorId = Long.parseLong(authentication.getName());
        return transferService.addPackagesToTransfer(actorId, input);
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public TransferResponse regenerateTransferCode(@Argument @Valid RegenerateTransferCodeInput input) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var actorId = Long.parseLong(authentication.getName());
        return transferService.regenerateTransferCode(actorId, input);
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public TransferResponse updateTransfer(@Argument @Valid UpdateTransferInput input) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var actorId = Long.parseLong(authentication.getName());
        return transferService.updateTransfer(actorId, input);
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public TransferResponse cancelTransfer(@Argument UUID transferId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var actorId = Long.parseLong(authentication.getName());
        return transferService.cancelTransfer(actorId, transferId);
    }

    // ──────────────────────────────────────────────
    // Unified accept — handles AUTO / SECURE / CONFIRM based on transfer's ruleType
    // ──────────────────────────────────────────────

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public TransferAcceptResult acceptTransfer(@Argument @Valid AcceptTransferInput input) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var actorId = Long.parseLong(authentication.getName());
        return transferService.acceptTransfer(actorId, input.transferId(), input.transferCode());
    }

    // ──────────────────────────────────────────────
    // CONFIRM mode mutations (owner-side)
    // ──────────────────────────────────────────────

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public TransferResponse confirmTransfer(@Argument UUID transferId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var actorId = Long.parseLong(authentication.getName());
        return transferService.confirmTransfer(actorId, transferId);
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public TransferResponse rejectTransfer(@Argument UUID transferId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var actorId = Long.parseLong(authentication.getName());
        return transferService.rejectTransfer(actorId, transferId);
    }
}
