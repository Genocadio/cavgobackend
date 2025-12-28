package com.gocavgo.Navigation.model;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.enhanced.SequenceStyleGenerator;

import java.io.Serializable;

public class TripIdGenerator extends SequenceStyleGenerator {
    @Override
    public Object generate(SharedSessionContractImplementor session, Object object) {
        if (object instanceof Trip) {
            Trip trip = (Trip) object;
            if (trip.getId() != null) {
                return trip.getId();
            }
        }
        return super.generate(session, object);
    }
}
