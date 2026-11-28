package com.repairshop.saas.masterdata.repository;

import com.repairshop.saas.masterdata.entity.MasterBrand;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MasterBrandRepository extends JpaRepository<MasterBrand, UUID> {

    /** Rows still using one image URL — the guard before deleting a superseded logo. */
    long countByImageUrl(String imageUrl);

    /** The row under a write lock — read, check and update a logo without racing another upload. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from MasterBrand b where b.id = :id")
    Optional<MasterBrand> lockById(@Param("id") UUID id);
}
