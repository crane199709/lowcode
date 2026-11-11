package io.saasforge.lowcode.screen.service;

import io.saasforge.lowcode.datasource.service.DatasetReferences;
import io.saasforge.lowcode.datasource.service.DeletionReferences;
import io.saasforge.lowcode.screen.entity.Screen;
import io.saasforge.lowcode.screen.mapper.ScreenMapper;
import io.saasforge.lowcode.screen.model.ScreenDraft;
import io.saasforge.sdk.abac.data.DataPermission;
import io.saasforge.sdk.security.MembershipGrantStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ScreenDatasetReferences implements DatasetReferences {
    private final ScreenMapper screens;
    private final JsonMapper json;
    private final DataPermission dataPermission;
    private final MembershipGrantStore grants;

    public ScreenDatasetReferences(
            ScreenMapper screens, JsonMapper json, DataPermission dataPermission, MembershipGrantStore grants) {
        this.screens = screens;
        this.json = json;
        this.dataPermission = dataPermission;
        this.grants = grants;
    }

    @Override
    public DeletionReferences find(UUID datasetId) {
        var references = new ArrayList<DeletionReferences.Reference>();
        boolean hidden = false;
        boolean canEdit = dataPermission.currentSubject().isEmpty()
                || grants.current().permissions().contains("screen:edit");
        boolean canView = canEdit || grants.current().permissions().contains("screen:view");
        for (var screen : screens.selectForDatasetReferences()) {
            if (contains(screen.getDraft(), datasetId) || contains(screen.getPublished(), datasetId)) {
                if (canView
                        && (canEdit || screen.getPublished() != null)
                        && dataPermission.permits("screen", attributes(screen))) {
                    references.add(new DeletionReferences.Reference(
                            screen.getId(), canEdit ? screen.getName() : screen.getPublishedName()));
                } else {
                    hidden = true;
                }
            }
        }
        return new DeletionReferences(references, hidden);
    }

    private boolean contains(String definition, UUID datasetId) {
        if (definition == null) {
            return false;
        }
        return json.readValue(definition, ScreenDraft.class).widgets().stream()
                .anyMatch(widget -> widget.isQueryWidget()
                        && datasetId
                                .toString()
                                .equals(widget.config().path("datasetId").asString("")));
    }

    private static HashMap<String, Object> attributes(Screen screen) {
        var attributes = new HashMap<String, Object>();
        attributes.put("ownerId", screen.getOwnerId());
        attributes.put("departmentPath", screen.getDepartmentPath());
        return attributes;
    }
}
