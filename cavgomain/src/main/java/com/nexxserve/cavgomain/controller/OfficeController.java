package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.OfficeRequestDto;
import com.nexxserve.cavgomain.dto.response.OfficeResponseDto;
import com.nexxserve.cavgomain.service.OfficeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/main/offices")
@RequiredArgsConstructor
public class OfficeController {

    private final OfficeService officeService;

    @PostMapping
    public OfficeResponseDto createOffice(@RequestBody OfficeRequestDto request) {
        return officeService.createOffice(request);
    }

    @PutMapping("/{id}")
    public OfficeResponseDto updateOffice(@PathVariable Long id, @RequestBody OfficeRequestDto request) {
        return officeService.updateOffice(id, request);
    }

    @GetMapping("/{id}")
    public OfficeResponseDto getOffice(@PathVariable Long id) {
        return officeService.findById(id);
    }

    @GetMapping
    public List<OfficeResponseDto> getOfficesByCompany(@RequestParam Long companyId) {
        return officeService.findByCompanyId(companyId);
    }

    @DeleteMapping("/{id}")
    public void deleteOffice(@PathVariable Long id) {
        officeService.deleteOffice(id);
    }
}
