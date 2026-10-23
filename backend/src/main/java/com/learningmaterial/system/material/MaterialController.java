package com.learningmaterial.system.material;

import com.learningmaterial.system.auth.AuthPrincipal;
import com.learningmaterial.system.common.ApiResponse;
import com.learningmaterial.system.material.MaterialDtos.MaterialView;
import com.learningmaterial.system.material.MaterialDtos.MaterialPatch;
import com.learningmaterial.system.material.MaterialDtos.NoteInput;
import com.learningmaterial.system.material.MaterialDtos.PageView;
import com.learningmaterial.system.material.MaterialDtos.TagView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class MaterialController {
    private final MaterialService materials;

    public MaterialController(MaterialService materials) { this.materials = materials; }

    @PostMapping("/materials")
    public ResponseEntity<ApiResponse<MaterialView>> create(@AuthenticationPrincipal AuthPrincipal principal,
                                                               @Valid @RequestBody NoteInput input,
                                                               HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.of(materials.createNote(principal.userId(), input), request));
    }

    @PostMapping(path = "/materials/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MaterialView>> uploadFile(@AuthenticationPrincipal AuthPrincipal principal,
                                                                   @RequestParam MultipartFile file,
                                                                   @RequestParam String title,
                                                                   @RequestParam(required = false) String subject,
                                                                   @RequestParam(required = false) String tagsJson,
                                                                   @RequestParam(defaultValue = "false") boolean allowDuplicate,
                                                                   HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(
            materials.uploadFile(principal.userId(), file, title, subject, tagsJson, allowDuplicate), request));
    }

    @GetMapping("/materials")
    public ApiResponse<PageView<MaterialView>> list(@AuthenticationPrincipal AuthPrincipal principal,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String subject,
                                                     @RequestParam(required = false) String tagId,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(defaultValue = "false") boolean favorite,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int pageSize,
                                                     HttpServletRequest request) {
        return ApiResponse.of(materials.list(principal.userId(), keyword, subject, tagId, status, favorite, page, pageSize), request);
    }

    @GetMapping("/materials/{id}")
    public ApiResponse<MaterialView> get(@AuthenticationPrincipal AuthPrincipal principal,
                                         @PathVariable String id, HttpServletRequest request) {
        return ApiResponse.of(materials.get(principal.userId(), id), request);
    }

    @GetMapping("/materials/{id}/file")
    public ResponseEntity<Resource> file(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        MaterialService.StoredFile file = materials.readFile(principal.userId(), id);
        String extension = "application/pdf".equals(file.mimeType()) ? "pdf"
            : "image/png".equals(file.mimeType()) ? "png" : "jpg";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.mimeType()))
            .contentLength(file.size())
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .headers(headers -> headers.setContentDisposition(ContentDisposition.inline().filename("material." + extension).build()))
            .body(new FileSystemResource(file.path()));
    }

    @PatchMapping("/materials/{id}")
    public ApiResponse<MaterialView> patch(@AuthenticationPrincipal AuthPrincipal principal,
                                           @PathVariable String id, @Valid @RequestBody MaterialPatch input,
                                           HttpServletRequest request) {
        return ApiResponse.of(materials.patch(principal.userId(), id, input), request);
    }

    @DeleteMapping("/materials/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthPrincipal principal,
                                       @PathVariable String id, @RequestParam int version) {
        materials.delete(principal.userId(), id, version);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tags")
    public ApiResponse<PageView<TagView>> tags(@AuthenticationPrincipal AuthPrincipal principal,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int pageSize,
                                                HttpServletRequest request) {
        return ApiResponse.of(materials.listTags(principal.userId(), keyword, page, pageSize), request);
    }
}
