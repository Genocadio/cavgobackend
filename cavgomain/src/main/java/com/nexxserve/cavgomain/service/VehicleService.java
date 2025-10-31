package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.message.VehicleSettingsMessage;
import com.nexxserve.cavgomain.dto.request.VehicleRequestDto;
import com.nexxserve.cavgomain.dto.request.VehicleAssignmentRequestDto;
import com.nexxserve.cavgomain.dto.request.VehicleSettingsUpdateDto;
import com.nexxserve.cavgomain.dto.response.VehicleAssignmentResponseDto;
import com.nexxserve.cavgomain.dto.response.VehicleLocationResponseDto;
import com.nexxserve.cavgomain.dto.response.VehicleResponseDto;
import com.nexxserve.cavgomain.dto.response.VehicleSettingsResponseDto;
import com.nexxserve.cavgomain.entity.*;
import com.nexxserve.cavgomain.enums.VehicleStatus;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.messaging.VehicleSettingsPublisher;
import com.nexxserve.cavgomain.repository.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final VehicleAssignmentRepository assignmentRepository;
    private final CompanyUserRepository companyUserRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final VehicleSettingsRepository settingsRepository;
    private final VehicleLocationRepository locationRepository;
    private final VehicleSettingsPublisher settingsPublisher;


    // CRUD methods
    public VehicleResponseDto createVehicle(VehicleRequestDto vehicle) {
        if (vehicleRepository.existsByLicensePlate(vehicle.getLicensePlate())) {
            throw new IllegalArgumentException("Vehicle with this license plate already exists");
        }
        Company company = companyRepository.findByCompanyCode(vehicle.getCompanyCode())
            .orElseThrow(() -> new IllegalArgumentException("Company with code '" + vehicle.getCompanyCode() + "' not found"));
        Vehicle newVehicle = vehicle.toEntity(company);
        return VehicleResponseDto.fromEntity(vehicleRepository.save(newVehicle));
    }

    public record VehicleCreateResult(VehicleResponseDto response, String initialPassword) {}

    public VehicleCreateResult createVehicleWithPassword(VehicleRequestDto vehicle) {
        if (vehicleRepository.existsByLicensePlate(vehicle.getLicensePlate())) {
            throw new IllegalArgumentException("Vehicle with this license plate already exists");
        }
        Company company = companyRepository.findByCompanyCode(vehicle.getCompanyCode())
            .orElseThrow(() -> new IllegalArgumentException("Company with code '" + vehicle.getCompanyCode() + "' not found"));
        Vehicle newVehicle = vehicle.toEntity(company);

        String initialPassword = generateSixDigitPassword();
        newVehicle.setPasswordHash(passwordEncoder.encode(initialPassword));

        Vehicle saved = vehicleRepository.save(newVehicle);
        
        // Initialize vehicle settings with defaults
        VehicleSettings settings = new VehicleSettings();
        settings.setVehicle(saved);
        settings.setLogout(true);
        settings.setDevmode(false);
        settings.setDeactivate(false);
        settings.setAppmode(false);
        settings.setSimulate(false);
        settingsRepository.save(settings);
        
        return new VehicleCreateResult(VehicleResponseDto.fromEntity(saved), initialPassword);
    }

    public VehicleResponseDto getByDriver(Long id) {
        CompanyUser driver = companyUserRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Driver not found"));

        if (driver.getRole() != CompanyUserRole.DRIVER) {
            throw new IllegalArgumentException("User is not a driver");
        }

        List<VehicleAssignment> activeAssignments = assignmentRepository.findActiveAssignmentsByDriver(id);

        if (activeAssignments.isEmpty()) {
            throw new EntityNotFoundException("No vehicle assigned to this driver");
        }

        Vehicle vehicle = activeAssignments.get(0).getVehicle();
        return VehicleResponseDto.fromEntity(vehicle, driver);
    }

    private String generateSixDigitPassword() {
        int code = (int) (Math.random() * 1_000_000);
        return String.format("%06d", code);
    }
    public Vehicle getVehicle(Long id) {
        return vehicleRepository.findByIdWithActiveAssignment(id)
                .orElseThrow(() -> new EntityNotFoundException("Vehicle not found"));
    }

    public List<VehicleResponseDto> getVehicleUser(Long userId) {
        CompanyUser user = companyUserRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));
        return getCompanyVehicles(user.getCompany().getId());
    }

    public VehicleResponseDto getVehicleResponse(Long id) {
        return VehicleResponseDto.fromEntity(getVehicle(id));
    }

    public List<VehicleResponseDto> getAllVehicles() {
        return vehicleRepository.findAllWithActiveAssignments().stream()
                .map(VehicleResponseDto::fromEntity)
                .toList();
    }

    public List<VehicleResponseDto> getCompanyVehicles(Long id) {
        return vehicleRepository.findByCompanyIdWithActiveAssignments(id).stream()
                .map(VehicleResponseDto::fromEntity)
                .toList();
    }


    public VehicleResponseDto updateVehicle(Long id, VehicleRequestDto updated) {
        Vehicle vehicle = getVehicle(id);
        vehicle.setMake(updated.getMake());
        vehicle.setModel(updated.getModel());
        vehicle.setLicensePlate(updated.getLicensePlate());
        vehicle.setVehicleType(updated.getVehicleType());
        vehicle.setStatus(updated.getStatus());
        return VehicleResponseDto.fromEntity(vehicleRepository.save(vehicle));
    }

    public void deleteVehicle(Long id) {
        vehicleRepository.deleteById(id);
    }

    // Assign vehicle to driver
   @Transactional
   public VehicleAssignmentResponseDto assignVehicleToDriver(Long vehicleId, Long driverId, String notes) {
       Vehicle vehicle = getVehicle(vehicleId);
       if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
           throw new IllegalStateException("Vehicle is not available for assignment");
       }

       CompanyUser driver = companyUserRepository.findById(driverId)
               .orElseThrow(() -> new EntityNotFoundException("Driver not found"));

       if (driver.getRole() != CompanyUserRole.DRIVER) {
           throw new IllegalArgumentException("User is not a driver");
       }

       // End any previous active assignment for this vehicle
       Optional<VehicleAssignment> activeAssignmentForVehicle =
               assignmentRepository.findActiveAssignmentByVehicle(vehicleId);
       activeAssignmentForVehicle.ifPresent(assignment -> {
           assignment.setUnassignedDate(LocalDateTime.now());
           assignment.setStatus(com.nexxserve.cavgomain.enums.AssignmentStatus.COMPLETED);
           assignmentRepository.save(assignment);
       });

       // Check if driver already has an active assignment
       List<VehicleAssignment> activeAssignmentsForDriver =
               assignmentRepository.findActiveAssignmentsByDriver(driverId);
       if (!activeAssignmentsForDriver.isEmpty()) {
           throw new IllegalStateException("Driver already has an assigned vehicle");
       }

       // Assign vehicle
       VehicleAssignment assignment = new VehicleAssignment();
       assignment.setVehicle(vehicle);
       assignment.setDriver(driver);
       assignment.setAssignedDate(LocalDateTime.now());
       assignment.setNotes(notes);

       vehicle.setStatus(VehicleStatus.AVAILABLE);
       vehicleRepository.save(vehicle);

       return VehicleAssignmentResponseDto.fromEntity(assignmentRepository.save(assignment));
   }

   @Transactional
   public VehicleAssignmentResponseDto assignVehicleToDriverWithDto(VehicleAssignmentRequestDto assignmentDto) {
       Vehicle vehicle = getVehicle(assignmentDto.getVehicleId());
       if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
           throw new IllegalStateException("Vehicle is not available for assignment");
       }

       CompanyUser driver = companyUserRepository.findById(assignmentDto.getDriverId())
               .orElseThrow(() -> new EntityNotFoundException("Driver not found"));

       if (driver.getRole() != CompanyUserRole.DRIVER) {
           throw new IllegalArgumentException("User is not a driver");
       }

       // End any previous active assignment for this vehicle
       Optional<VehicleAssignment> activeAssignmentForVehicle =
               assignmentRepository.findActiveAssignmentByVehicle(assignmentDto.getVehicleId());
       activeAssignmentForVehicle.ifPresent(assignment -> {
           assignment.setUnassignedDate(LocalDateTime.now());
           assignment.setStatus(com.nexxserve.cavgomain.enums.AssignmentStatus.COMPLETED);
           assignmentRepository.save(assignment);
       });

       // Check if driver already has an active assignment
       List<VehicleAssignment> activeAssignmentsForDriver =
               assignmentRepository.findActiveAssignmentsByDriver(assignmentDto.getDriverId());
       if (!activeAssignmentsForDriver.isEmpty()) {
           throw new IllegalStateException("Driver already has an assigned vehicle");
       }

       // Create assignment using DTO
       VehicleAssignment assignment = assignmentDto.toEntity(vehicle, driver);
       assignment.setStatus(assignmentDto.getStatus());

       vehicle.setStatus(VehicleStatus.OCCUPIED);
       vehicleRepository.save(vehicle);

       return VehicleAssignmentResponseDto.fromEntity(assignmentRepository.save(assignment));
   }

   public VehicleResponseDto loginVehicle(String companyCode, String licensePlate, String password, String newPubKey) {
       Vehicle vehicle = vehicleRepository.findByLicensePlateWithActiveAssignment(licensePlate)
               .orElseThrow(() -> new EntityNotFoundException("Vehicle not found"));

       if (!vehicle.getCompany().getCompanyCode().equals(companyCode)) {
           throw new IllegalArgumentException("Company code does not match vehicle");
       }

       String storedHash = vehicle.getPasswordHash();
       if (storedHash == null || !passwordEncoder.matches(password, storedHash)) {
           throw new IllegalArgumentException("Invalid password");
       }

       // Check if logout is true (allowing login)
       VehicleSettings settings = settingsRepository.findByVehicleId(vehicle.getId())
               .orElseThrow(() -> new IllegalStateException("Vehicle settings not found"));
       
       if (!settings.getLogout()) {
           throw new IllegalStateException("Vehicle is already logged in. Only one client per vehicle is allowed.");
       }

       vehicle.setPubKey(newPubKey);
       vehicle.setLastOnlineAt(LocalDateTime.now());
       vehicleRepository.save(vehicle);

       // Set logout to false after successful login
       settings.setLogout(false);
       settingsRepository.save(settings);

       return VehicleResponseDto.fromEntity(vehicle);
   }

   public String regenerateVehiclePassword(String licensePlate) {
       Vehicle vehicle = vehicleRepository.findByLicensePlateWithActiveAssignment(licensePlate)
               .orElseThrow(() -> new EntityNotFoundException("Vehicle not found"));

        String newPassword = generateSixDigitPassword();
        vehicle.setPasswordHash(passwordEncoder.encode(newPassword));
        vehicleRepository.save(vehicle);
        return newPassword;
   }

   @Transactional
   public VehicleAssignmentResponseDto unassignVehicle(Long vehicleId) {
       Vehicle vehicle = getVehicle(vehicleId);
       
       // Find active assignment for this vehicle
       Optional<VehicleAssignment> activeAssignment = assignmentRepository.findActiveAssignmentByVehicle(vehicleId);
       if (activeAssignment.isEmpty()) {
           throw new IllegalStateException("No active assignment found for this vehicle");
       }
       
       VehicleAssignment assignment = activeAssignment.get();
       assignment.setUnassignedDate(LocalDateTime.now());
       assignment.setStatus(com.nexxserve.cavgomain.enums.AssignmentStatus.COMPLETED);
       assignmentRepository.save(assignment);
       
       // Set vehicle status to AVAILABLE
       vehicle.setStatus(VehicleStatus.AVAILABLE);
       vehicleRepository.save(vehicle);
       
       return VehicleAssignmentResponseDto.fromEntity(assignment);
   }

   @Transactional
   public VehicleAssignmentResponseDto swapAssignment(Long vehicleId, Long newDriverId) {
       Vehicle vehicle = getVehicle(vehicleId);
       
       CompanyUser newDriver = companyUserRepository.findById(newDriverId)
               .orElseThrow(() -> new EntityNotFoundException("Driver not found"));
       
       if (newDriver.getRole() != CompanyUserRole.DRIVER) {
           throw new IllegalArgumentException("User is not a driver");
       }
       
       // Find current assignment for this vehicle (if any)
       Optional<VehicleAssignment> currentAssignment = assignmentRepository.findActiveAssignmentByVehicle(vehicleId);
       
       // Find new driver's current assignment (if any)
       List<VehicleAssignment> newDriverAssignments = assignmentRepository.findActiveAssignmentsByDriver(newDriverId);
       
       // End current assignment for the vehicle if it exists
       if (currentAssignment.isPresent()) {
           VehicleAssignment assignment = currentAssignment.get();
           assignment.setUnassignedDate(LocalDateTime.now());
           assignment.setStatus(com.nexxserve.cavgomain.enums.AssignmentStatus.COMPLETED);
           assignmentRepository.save(assignment);
       }
       
       // End new driver's current assignment if it exists (true swap)
       if (!newDriverAssignments.isEmpty()) {
           VehicleAssignment newDriverAssignment = newDriverAssignments.get(0);
           newDriverAssignment.setUnassignedDate(LocalDateTime.now());
           newDriverAssignment.setStatus(com.nexxserve.cavgomain.enums.AssignmentStatus.COMPLETED);
           assignmentRepository.save(newDriverAssignment);
           
           // Set the old vehicle to AVAILABLE
           Vehicle oldVehicle = newDriverAssignment.getVehicle();
           oldVehicle.setStatus(VehicleStatus.AVAILABLE);
           vehicleRepository.save(oldVehicle);
       }
       
       // Create new assignment
       VehicleAssignment newAssignment = new VehicleAssignment();
       newAssignment.setVehicle(vehicle);
       newAssignment.setDriver(newDriver);
       newAssignment.setAssignedDate(LocalDateTime.now());
       newAssignment.setStatus(com.nexxserve.cavgomain.enums.AssignmentStatus.ACTIVE);
       
       if (currentAssignment.isPresent()) {
           if (!newDriverAssignments.isEmpty()) {
               newAssignment.setNotes("Full swap: Driver " + newDriverId + " from vehicle " + 
                   newDriverAssignments.get(0).getVehicle().getId() + " to vehicle " + vehicleId + 
                   ", Driver " + currentAssignment.get().getDriver().getId() + " unassigned");
           } else {
               newAssignment.setNotes("Assignment swapped from driver ID: " + currentAssignment.get().getDriver().getId());
           }
       } else {
           if (!newDriverAssignments.isEmpty()) {
               newAssignment.setNotes("Driver " + newDriverId + " swapped from vehicle " + 
                   newDriverAssignments.get(0).getVehicle().getId() + " to vehicle " + vehicleId);
           } else {
               newAssignment.setNotes("New assignment created via swap");
           }
       }
       
       // Keep vehicle status as AVAILABLE (not OCCUPIED)
       vehicle.setStatus(VehicleStatus.AVAILABLE);
       vehicleRepository.save(vehicle);
       
       return VehicleAssignmentResponseDto.fromEntity(assignmentRepository.save(newAssignment));
   }

   @Transactional
   public VehicleAssignmentResponseDto swapDriverAssignment(Long currentDriverId, Long newDriverId) {
       CompanyUser currentDriver = companyUserRepository.findById(currentDriverId)
               .orElseThrow(() -> new EntityNotFoundException("Current driver not found"));
       
       CompanyUser newDriver = companyUserRepository.findById(newDriverId)
               .orElseThrow(() -> new EntityNotFoundException("New driver not found"));
       
       if (currentDriver.getRole() != CompanyUserRole.DRIVER) {
           throw new IllegalArgumentException("Current user is not a driver");
       }
       
       if (newDriver.getRole() != CompanyUserRole.DRIVER) {
           throw new IllegalArgumentException("New user is not a driver");
       }
       
       // Find current driver's active assignment
       List<VehicleAssignment> currentDriverAssignments = assignmentRepository.findActiveAssignmentsByDriver(currentDriverId);
       if (currentDriverAssignments.isEmpty()) {
           throw new IllegalStateException("Current driver has no active assignment");
       }
       
       // Find new driver's current assignment (if any)
       List<VehicleAssignment> newDriverAssignments = assignmentRepository.findActiveAssignmentsByDriver(newDriverId);
       
       VehicleAssignment currentAssignment = currentDriverAssignments.get(0);
       Vehicle vehicle = currentAssignment.getVehicle();
       
       // End current assignment
       currentAssignment.setUnassignedDate(LocalDateTime.now());
       currentAssignment.setStatus(com.nexxserve.cavgomain.enums.AssignmentStatus.COMPLETED);
       assignmentRepository.save(currentAssignment);
       
       // End new driver's current assignment if it exists (true swap)
       if (!newDriverAssignments.isEmpty()) {
           VehicleAssignment newDriverAssignment = newDriverAssignments.get(0);
           newDriverAssignment.setUnassignedDate(LocalDateTime.now());
           newDriverAssignment.setStatus(com.nexxserve.cavgomain.enums.AssignmentStatus.COMPLETED);
           assignmentRepository.save(newDriverAssignment);
           
           // Set the old vehicle to AVAILABLE
           Vehicle oldVehicle = newDriverAssignment.getVehicle();
           oldVehicle.setStatus(VehicleStatus.AVAILABLE);
           vehicleRepository.save(oldVehicle);
       }
       
       // Create new assignment
       VehicleAssignment newAssignment = new VehicleAssignment();
       newAssignment.setVehicle(vehicle);
       newAssignment.setDriver(newDriver);
       newAssignment.setAssignedDate(LocalDateTime.now());
       newAssignment.setStatus(com.nexxserve.cavgomain.enums.AssignmentStatus.ACTIVE);
       
       if (!newDriverAssignments.isEmpty()) {
           newAssignment.setNotes("Full driver swap: Driver " + newDriverId + " from vehicle " + 
               newDriverAssignments.get(0).getVehicle().getId() + " to vehicle " + vehicle.getId() + 
               ", Driver " + currentDriverId + " unassigned");
       } else {
           newAssignment.setNotes("Driver swapped from driver ID: " + currentDriverId);
       }
       
       // Keep vehicle status as AVAILABLE (not OCCUPIED)
       vehicle.setStatus(VehicleStatus.AVAILABLE);
       vehicleRepository.save(vehicle);
       
       return VehicleAssignmentResponseDto.fromEntity(assignmentRepository.save(newAssignment));
   }

   // Vehicle Settings Methods
   @Transactional
   public VehicleSettingsResponseDto getVehicleSettings(Long vehicleId) {
       Vehicle vehicle = getVehicle(vehicleId);
       VehicleSettings settings = settingsRepository.findByVehicleId(vehicleId)
               .orElseGet(() -> {
                   // Create new settings with defaults if they don't exist
                   VehicleSettings newSettings = new VehicleSettings();
                   newSettings.setVehicle(vehicle);
                   newSettings.setLogout(true);
                   newSettings.setDevmode(false);
                   newSettings.setDeactivate(false);
                   newSettings.setAppmode(false);
                   newSettings.setSimulate(false);
                   return settingsRepository.save(newSettings);
               });
       return VehicleSettingsResponseDto.fromEntity(settings);
   }

   @Transactional
   public VehicleSettingsResponseDto updateVehicleSettings(Long vehicleId, VehicleSettingsUpdateDto updateDto) {
       Vehicle vehicle = getVehicle(vehicleId);
       VehicleSettings settings = settingsRepository.findByVehicleId(vehicleId)
               .orElseGet(() -> {
                   // Create new settings with defaults if they don't exist
                   VehicleSettings newSettings = new VehicleSettings();
                   newSettings.setVehicle(vehicle);
                   newSettings.setLogout(true);
                   newSettings.setDevmode(false);
                   newSettings.setDeactivate(false);
                   newSettings.setAppmode(false);
                   newSettings.setSimulate(false);
                   return settingsRepository.save(newSettings);
               });

       // Update only non-null fields
       if (updateDto.getLogout() != null) {
           settings.setLogout(updateDto.getLogout());
       }
       if (updateDto.getDevmode() != null) {
           settings.setDevmode(updateDto.getDevmode());
       }
       if (updateDto.getDeactivate() != null) {
           settings.setDeactivate(updateDto.getDeactivate());
       }
       if (updateDto.getAppmode() != null) {
           settings.setAppmode(updateDto.getAppmode());
       }
       if (updateDto.getSimulate() != null) {
           settings.setSimulate(updateDto.getSimulate());
       }

       VehicleSettings saved = settingsRepository.save(settings);

       // Publish settings update to RabbitMQ
       VehicleSettingsMessage message = VehicleSettingsMessage.fromEntity(saved, vehicle.getLicensePlate());
       settingsPublisher.publishSettingsUpdate(message, vehicleId);

       return VehicleSettingsResponseDto.fromEntity(saved);
   }

   // Vehicle Location Methods
   public List<VehicleLocationResponseDto> getVehicleLocations(Long vehicleId, LocalDateTime since) {
       LocalDateTime cutoff = since != null ? since : LocalDateTime.now().minusHours(48);
       return locationRepository.findByVehicleIdAndRecordedAtAfter(vehicleId, cutoff)
               .stream()
               .map(VehicleLocationResponseDto::fromEntity)
               .toList();
   }

   public VehicleLocationResponseDto getLatestVehicleLocation(Long vehicleId) {
       return locationRepository.findTopByVehicleIdOrderByRecordedAtDesc(vehicleId)
               .map(VehicleLocationResponseDto::fromEntity)
               .orElse(null);
   }
}