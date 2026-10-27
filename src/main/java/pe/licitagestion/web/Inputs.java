package pe.licitagestion.web;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;

public final class Inputs {
    private Inputs(){}
    public record TenderInput(@NotBlank @Size(max=80) String code,@NotBlank @Size(max=240) String title,
        @NotBlank @Size(max=200) String entity,@NotBlank @Size(max=20000) String description,@NotNull LocalDate publishedOn,
        @NotNull OffsetDateTime deadline,@NotBlank String status,@NotBlank String priority,@NotNull Long ownerId,
        @Size(max=10000) String notes,@NotNull @Size(max=100) List<Long> accessIds,@Min(0) int version){}
    public record RequirementInput(@NotBlank @Size(max=1500) String description,@NotBlank String kind,@NotBlank String status,
        @NotNull Long ownerId,@NotNull OffsetDateTime deadline,Long documentId,@Size(max=5000) String notes,@Min(0) int version){}
    public record TaskInput(@NotBlank @Size(max=200) String name,@Size(max=10000) String description,@NotNull Long ownerId,
        @NotNull OffsetDateTime deadline,@NotBlank String priority,@NotBlank String status,@Size(max=5000) String notes,@Min(0) int version){}
    public record MilestoneInput(@NotBlank @Size(max=200) String name,@NotBlank String kind,@NotNull OffsetDateTime deadline,
        @NotNull Long ownerId,boolean completed,@Size(max=5000) String notes,@Min(0) int version){}
    public record UserInput(@NotBlank @Size(max=120) String name,@NotBlank @Email @Size(max=254) String email,
        @Size(max=72) String password,@NotBlank String role,boolean enabled,@Pattern(regexp="^$|^\\+[1-9][0-9]{7,14}$") String phone,boolean whatsappOptIn){}
    public record Question(@NotBlank @Size(max=1500) String question){}
    public record Review(@NotBlank String action,@NotBlank @Size(max=1500) String description,@NotBlank String kind,
        @NotNull Long ownerId,@NotNull OffsetDateTime deadline){}
    public record NotificationInput(@NotBlank String alertKey,@NotNull Long recipientId){}
}

