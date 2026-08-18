package com.gocavgo.delivary.service.notification;

import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.notification.NoticeEventType;
import com.gocavgo.delivary.enums.transfer.TransferStatus;

/**
 * Exhaustive switch-expression mappers that force a compilation error when
 * PackageStatus or TransferStatus gains a new value, ensuring the notice
 * system stays in sync.
 */
public final class NoticeEventMapper {

    private NoticeEventMapper() {
    }

    public static NoticeEventType fromPackageStatus(PackageStatus status) {
        return switch (status) {
            case CREATED -> NoticeEventType.PACKAGE_CREATED;
            case ACCEPTED -> NoticeEventType.PACKAGE_ACCEPTED;
            case PICKED_UP -> NoticeEventType.PACKAGE_PICKED_UP;
            case IN_TRANSIT -> NoticeEventType.PACKAGE_IN_TRANSIT;
            case PENDING_CONFIRMATION -> NoticeEventType.PACKAGE_DELIVERY_INITIATED;
            case DELIVERED -> NoticeEventType.PACKAGE_DELIVERED;
            case COMPLETED -> NoticeEventType.PACKAGE_COMPLETED;
            case CANCELLED -> NoticeEventType.PACKAGE_CANCELLED;
            case ORIGIN_OFFICE -> NoticeEventType.PACKAGE_ORIGIN_OFFICE;
            case ASSIGNED_DRIVER -> NoticeEventType.PACKAGE_ASSIGNED_DRIVER;
            case DESTINATION_OFFICE -> NoticeEventType.PACKAGE_DESTINATION_OFFICE;
            case READY_FOR_COLLECTION -> NoticeEventType.PACKAGE_READY_FOR_COLLECTION;
        };
    }

    public static NoticeEventType fromTransferStatus(TransferStatus status) {
        return switch (status) {
            case PENDING -> NoticeEventType.TRANSFER_PENDING;
            case REQUESTED -> NoticeEventType.TRANSFER_REQUESTED;
            case DONE -> NoticeEventType.TRANSFER_DONE;
            case CANCELED -> NoticeEventType.TRANSFER_CANCELED;
        };
    }
}
