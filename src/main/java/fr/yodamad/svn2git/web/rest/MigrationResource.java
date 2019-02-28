package fr.yodamad.svn2git.web.rest;

import com.codahale.metrics.annotation.Timed;
import fr.yodamad.svn2git.domain.Mapping;
import fr.yodamad.svn2git.domain.Migration;
import fr.yodamad.svn2git.domain.MigrationHistory;
import fr.yodamad.svn2git.domain.MigrationRemovedFile;
import fr.yodamad.svn2git.domain.enumeration.StatusEnum;
import fr.yodamad.svn2git.repository.MigrationRepository;
import fr.yodamad.svn2git.service.MappingService;
import fr.yodamad.svn2git.service.MigrationHistoryService;
import fr.yodamad.svn2git.service.MigrationManager;
import fr.yodamad.svn2git.service.MigrationRemovedFileService;
import fr.yodamad.svn2git.web.rest.errors.BadRequestAlertException;
import fr.yodamad.svn2git.web.rest.util.HeaderUtil;
import fr.yodamad.svn2git.web.rest.util.PaginationUtil;
import io.github.jhipster.web.util.ResponseUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * REST controller for managing Migration.
 */
@RestController
@RequestMapping("/api")
public class MigrationResource {

    private final Logger log = LoggerFactory.getLogger(MigrationResource.class);

    private static final String ENTITY_NAME = "migration";

    private final MigrationRepository migrationRepository;

    private final MigrationManager migrationManager;

    private final MigrationHistoryService migrationHistoryService;

    private final MappingService mappingService;

    private final MigrationRemovedFileService mrfService;

    public MigrationResource(MigrationRepository migrationRepository, MigrationManager migrationManager,
                             MigrationHistoryService migrationHistoryService, MappingService mappingService,
                             MigrationRemovedFileService migrationRemovedFileService) {
        this.migrationRepository = migrationRepository;
        this.migrationManager = migrationManager;
        this.migrationHistoryService = migrationHistoryService;
        this.mappingService = mappingService;
        this.mrfService = migrationRemovedFileService;
    }

    /**
     * POST  /migrations : Create a new migration.
     *
     * @param migration the migration to create
     * @return the ResponseEntity with status 201 (Created) and with body the new migration, or with status 400 (Bad Request) if the migration has already an ID
     * @throws URISyntaxException if the Location URI syntax is incorrect
     */
    @PostMapping("/migrations")
    @Timed
    public ResponseEntity<Migration> createMigration(@Valid @RequestBody Migration migration) throws URISyntaxException {
        log.debug("REST request to save Migration : {}", migration);
        if (migration.getId() != null) {
            throw new BadRequestAlertException("A new migration cannot already have an ID", ENTITY_NAME, "idexists");
        }

        migration.setDate(LocalDate.now());
        migration.setStatus(StatusEnum.WAITING);

        Migration result = init(migration);
        migrationManager.startMigration(result.getId(), Boolean.FALSE);

        return ResponseEntity.created(new URI("/api/migrations/" + result.getId()))
            .headers(HeaderUtil.createEntityCreationAlert(ENTITY_NAME, result.getId().toString()))
            .body(migration);
    }

    /**
     * Retry a migration
     * @param id Migration ID to be retried
     * @return Migration initialized for retry
     * @throws URISyntaxException
     */
    @PostMapping("/migrations/{id}/retry")
    @Timed
    public ResponseEntity<Long> retryMigraton(@PathVariable Long id) throws URISyntaxException {
        log.debug("REST request to retry Migration : {}", id);
        Migration mig = migrationRepository.findById(id).orElseThrow(() -> new BadRequestAlertException("Migration cannot be retried", ENTITY_NAME, "iddonotexist"));

        log.debug("Create a new migration to retry");
        mig.setId(null);
        Migration result = init(mig);
        migrationManager.startMigration(result.getId(), Boolean.TRUE);

        return ResponseEntity.created(new URI("/api/migrations/" + result.getId()))
            .headers(HeaderUtil.createEntityCreationAlert(ENTITY_NAME, result.getId().toString()))
            .body(id);
    }

    /**
     * Initialize a migration and its mappings
     * @param migration Migration to initialize
     * @return Migration initialized
     */
    private Migration init(Migration migration) {
        Migration result = migrationRepository.save(migration);

        if (migration.getMappings() != null && !migration.getMappings().isEmpty()) {
            migration.getMappings().stream()
                .filter(mp -> !StringUtils.isEmpty(mp.getGitDirectory()))
                .forEach(mapping -> {
                    // Remove ID from static mapping
                    mapping.setId(0L);
                    mapping.setMigration(result.getId());
                    mappingService.save(mapping);
                });
        }
        return result;
    }

    /**
     * PUT  /migrations : Updates an existing migration.
     *
     * @param migration the migration to update
     * @return the ResponseEntity with status 200 (OK) and with body the updated migration,
     * or with status 400 (Bad Request) if the migration is not valid,
     * or with status 500 (Internal Server Error) if the migration couldn't be updated
     * @throws URISyntaxException if the Location URI syntax is incorrect
     */
    @PutMapping("/migrations")
    @Timed
    public ResponseEntity<Migration> updateMigration(@Valid @RequestBody Migration migration) throws URISyntaxException {
        log.debug("REST request to update Migration : {}", migration);
        if (migration.getId() == null) {
            throw new BadRequestAlertException("Invalid id", ENTITY_NAME, "idnull");
        }
        Migration result = migrationRepository.save(migration);
        return ResponseEntity.ok()
            .headers(HeaderUtil.createEntityUpdateAlert(ENTITY_NAME, migration.getId().toString()))
            .body(result);
    }

    /**
     * GET  /migrations : get all the migrations.
     *
     * @param pageable the pagination information
     * @return the ResponseEntity with status 200 (OK) and the list of migrations in body
     */
    @GetMapping("/migrations")
    @Timed
    public ResponseEntity<List<Migration>> getAllMigrations(Pageable pageable) {
        log.debug("REST request to get a page of Migrations");
        Page<Migration> page = migrationRepository.findAll(pageable);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(page, "/api/migrations");
        return new ResponseEntity<>(page.getContent(), headers, HttpStatus.OK);
    }

    /**
     * GET  /migrations/user/:user : get all the migrations for a given user.
     *
     * @param user username for migrations
     * @param pageable the pagination information
     * @return the ResponseEntity with status 200 (OK) and the list of migrations in body
     */
    @GetMapping("/migrations/user/{user}")
    @Timed
    public ResponseEntity<List<Migration>> getMigrationsByUser(@PathVariable String user, Pageable pageable) {
        log.debug("REST request to get a page of Migrations for a given user");
        Page<Migration> page = migrationRepository.findAllByUser(user, pageable);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(page, "/api/migrations/user/");
        return new ResponseEntity<>(page.getContent(), headers, HttpStatus.OK);
    }

    /**
     * GET  /migrations/group/:group : get all the migrations for a given group.
     *
     * @param group group for migrations
     * @param pageable the pagination information
     * @return the ResponseEntity with status 200 (OK) and the list of migrations in body
     */
    @GetMapping("/migrations/group/{group}")
    @Timed
    public ResponseEntity<List<Migration>> getMigrationsByGroup(@PathVariable String group, Pageable pageable) {
        log.debug("REST request to get a page of Migrations for a given group");
        Page<Migration> page = migrationRepository.findAllBySvnGroup(group, pageable);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(page, "/api/migrations/group/");
        return new ResponseEntity<>(page.getContent(), headers, HttpStatus.OK);
    }

    /**
     * GET  /migrations/:id : get the "id" migration.
     *
     * @param id the id of the migration to retrieve
     * @return the ResponseEntity with status 200 (OK) and with body the migration, or with status 404 (Not Found)
     */
    @GetMapping("/migrations/{id}")
    @Timed
    public ResponseEntity<Migration> getMigration(@PathVariable Long id) {
        log.debug("REST request to get Migration : {}", id);
        Optional<Migration> migration = migrationRepository.findById(id);
        return ResponseUtil.wrapOrNotFound(migration);
    }

    /**
     * GET  /migrations/:id/histories : get the histories linked to "id" migration.
     *
     * @param id the id of the migration to retrieve
     * @return the ResponseEntity with status 200 (OK) and with body the histories array, or with status 404 (Not Found)
     */
    @GetMapping("/migrations/{id}/histories")
    @Timed
    public ResponseEntity<List<MigrationHistory>> getMigrationHistories(@PathVariable Long id) {
        log.debug("REST request to get Migration : {}", id);
        List<MigrationHistory> histories = migrationHistoryService.findAllForMigration(id);
        return new ResponseEntity<>(histories, null, HttpStatus.OK);
    }

    /**
     * GET  /migrations/:id/mappings : get the mappings linked to "id" migration.
     *
     * @param id the id of the migration to retrieve
     * @return the ResponseEntity with status 200 (OK) and with body the mappings array, or with status 404 (Not Found)
     */
    @GetMapping("/migrations/{id}/mappings")
    @Timed
    public ResponseEntity<List<Mapping>> getMigrationMappings(@PathVariable Long id) {
        log.debug("REST request to get Migration : {}", id);
        List<Mapping> mappings = mappingService.findAllForMigration(id);
        return new ResponseEntity<>(mappings, null, HttpStatus.OK);
    }

    /**
     * DELETE  /migrations/:id : delete the "id" migration.
     *
     * @param id the id of the migration to delete
     * @return the ResponseEntity with status 200 (OK)
     */
    @DeleteMapping("/migrations/{id}")
    @Timed
    public ResponseEntity<Void> deleteMigration(@PathVariable Long id) {
        log.debug("REST request to delete Migration : {}", id);

        migrationRepository.deleteById(id);
        return ResponseEntity.ok().headers(HeaderUtil.createEntityDeletionAlert(ENTITY_NAME, id.toString())).build();
    }

    /**
     * Get removed files for a given migration, either count or list them.
     * @param id Migration Id
     * @param action Action (list or count)
     * @return
     */
    @GetMapping("/migrations/{id}/removed-files")
    @Timed
    public ResponseEntity<?> getRemovedFiles(@PathVariable Long id, @RequestParam String action) {
        if (action.equalsIgnoreCase("list")) {
            List<MigrationRemovedFile> files = this.mrfService.getAll4Migration(id);
            return new ResponseEntity<>(files, null, HttpStatus.OK);
        } else if (action.equalsIgnoreCase("count")) {
            Integer count = this.mrfService.countAll4Migration(id);
            return new ResponseEntity<>(count, null, HttpStatus.OK);
        } else {
            return ResponseEntity.badRequest().build();
        }

    }
}
